package mesosphere.marathon
package core.election

import akka.actor.Cancellable
import akka.stream.scaladsl.{ Source, SourceQueueWithComplete }
import akka.stream.OverflowStrategy
import com.typesafe.scalalogging.StrictLogging
import java.util
import java.util.Collections
import java.util.concurrent.{ ExecutorService, TimeUnit }
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, Timer }
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.util.LifeCycledCloseableLike
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{ ACLProvider, CuratorWatcher, UnhandledErrorListener }
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.{ AuthInfo, CuratorFrameworkFactory }
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.{ KeeperException, WatchedEvent, ZooDefs }
import org.apache.zookeeper.data.ACL
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.Try

object CuratorElectionStream extends StrictLogging {
  /**
    * Connects to Zookeeper and offers leadership; monitors leader state. Watches for leadership changes (leader
    * changed, was elected leader, lost leadership), and emits events accordingly.
    *
    * Materialized cancellable is used to abdicate leadership; which will do so followed by a closing of the stream.
    */
  def apply(
    clientCloseable: LifeCycledCloseableLike[CuratorFramework],
    zooKeeperLeaderPath: String,
    zooKeeperConnectionTimeout: FiniteDuration,
    hostPort: String,
    singleThreadExecutor: ExecutorService): Source[LeadershipState, Cancellable] = {
    val singleThreadEC = ExecutionContext.fromExecutor(singleThreadExecutor)
    Source.queue[LeadershipState](16, OverflowStrategy.dropHead)
      .mapMaterializedValue { sq =>
        val emitterLogic = new CuratorEventEmitter(singleThreadExecutor, clientCloseable, zooKeeperLeaderPath, hostPort, sq)
        emitterLogic.start()
        sq.watchCompletion().onComplete { _ => emitterLogic.cancel() }(singleThreadEC)
        emitterLogic
      }
      .initialTimeout(zooKeeperConnectionTimeout)
      .concat(Source.single(LeadershipState.Standby(None)))
      .via(EnrichedFlow.dedup(LeadershipState.Standby(None)))
      .map { e =>
        e match {
          case LeadershipState.ElectedAsLeader =>
            logger.info("Leader won.")
          case LeadershipState.Standby(currentLeader) =>
            logger.info(s"Leader defeated. Current leader: ${currentLeader.getOrElse("-")}")
        }
        e
      }
  }

  @SuppressWarnings(Array("CatchThrowable"))
  private class CuratorEventEmitter(
      singleThreadExecutor: ExecutorService,
      clientCloseable: LifeCycledCloseableLike[CuratorFramework],
      zooKeeperLeaderPath: String,
      hostPort: String,
      sq: SourceQueueWithComplete[LeadershipState]) extends Cancellable {

    val client = clientCloseable.closeable
    private lazy val singleThreadEC: ExecutionContext = ExecutionContext.fromExecutor(singleThreadExecutor)
    private lazy val leaderHostPortMetric: Timer = Metrics.timer(ServiceMetric, getClass, "current-leader-host-port")
    private val curatorLeaderLatchPath = zooKeeperLeaderPath + "-curator"
    private lazy val latch = new LeaderLatch(client, curatorLeaderLatchPath, hostPort)
    @volatile private var isStarted = false
    @volatile private var _isCancelled = false

    /* It is important that we re-register our watch _BEFORE_ we get the current the current leader. In Zookeeper,
     * watches are one-time use only.
     *
     * The timeline of events looks like this
     *
     * 1) We register a watch
     * 2) We query the current leader
     * 3) We receive an event (indicating child removal / addition)
     * 4) Repeat step 1
     *
     * By re-registering the watch before querying the state, we will not miss out on the latest leader change.
     */
    def longPollLeaderChange(retries: Int = 0): Unit = Future {
      try {
        if (latch.getState == LeaderLatch.State.STARTED)
          client.getChildren
            .usingWatcher(new CuratorWatcher {
              override def process(event: WatchedEvent): Unit =
                if (!_isCancelled) longPollLeaderChange()
            })
            .forPath(curatorLeaderLatchPath)
        emitLeader()
      } catch {
        case ex: KeeperException.NoNodeException if retries < 100 =>
          // Wait for node to be created
          logger.info("retrying")
          Thread.sleep(retries * 10L)
          longPollLeaderChange(retries + 1)
        case ex: Throwable =>
          sq.fail(ex)
      }
    }(singleThreadEC)

    /**
      * Emit current leader. Does not fail on connection error
      */
    private def emitLeader(): Unit = {
      val currentLeader = leaderHostPortMetric.blocking {
        if (client.getState == CuratorFrameworkState.STOPPED) {
          None
        } else {
          try {
            Some(latch.getLeader.getId)
          } catch {
            case ex: Throwable =>
              logger.error("Error while getting current leader", ex)
              None
          }
        }
      }

      currentLeader match {
        case Some(`hostPort`) =>
          sq.offer(LeadershipState.ElectedAsLeader)
        case otherwise =>
          sq.offer(LeadershipState.Standby(otherwise))
      }
    }

    private val closeHook: () => Unit = cancel

    def start(): Unit = synchronized {
      assert(!isStarted, "already started")
      isStarted = true
      // We register the beforeClose hook to ensure that we have an opportunity to remove the latch entry before we lose
      // our connection to Zookeeper
      clientCloseable.beforeClose(closeHook)
      try {
        logger.info("starting leader latch")
        latch.start()
        longPollLeaderChange()

      } catch {
        case ex: Throwable =>
          logger.error("Error starting curator election event emitter")
          sq.fail(ex)
      }
    }

    override def isCancelled: Boolean = _isCancelled

    override def cancel(): Boolean = synchronized {
      require(isStarted, "not started")
      if (!_isCancelled) {
        clientCloseable.removeBeforeClose(closeHook)
        _isCancelled = true
        // shutdown hook remove will throw if already shutting down; swallow the exception and continue.

        try {
          logger.info("Closing leader latch")
          latch.close()
          logger.info("Leader latch closed")
        } catch {
          case ex: Throwable =>
            logger.error("Error closing CuratorElectionStream latch", ex)
        }
        Try(sq.complete()) // if not already completed
      }
      _isCancelled
    }
  }

  def newCuratorConnection(config: ZookeeperConf) = {
    logger.info(s"Will do leader election through ${config.zkHosts}")

    // let the world read the leadership information as some setups depend on that to find Marathon
    val defaultAcl = new util.ArrayList[ACL]()
    defaultAcl.addAll(config.zkDefaultCreationACL)
    defaultAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    val aclProvider = new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = defaultAcl
      override def getAclForPath(path: String): util.List[ACL] = defaultAcl
    }

    val retryPolicy = new ExponentialBackoffRetry(1.second.toMillis.toInt, 10)
    val builder = CuratorFrameworkFactory.builder().
      connectString(config.zkHosts).
      sessionTimeoutMs(config.zooKeeperSessionTimeout().toInt).
      connectionTimeoutMs(config.zooKeeperConnectionTimeout().toInt).
      aclProvider(aclProvider).
      retryPolicy(retryPolicy)

    // optionally authenticate
    val client = (config.zkUsername, config.zkPassword) match {
      case (Some(user), Some(pass)) =>
        builder.authorization(Collections.singletonList(
          new AuthInfo("digest", (user + ":" + pass).getBytes("UTF-8"))))
          .build()
      case _ =>
        builder.build()
    }

    val listener = new LastErrorListener
    client.getUnhandledErrorListenable().addListener(listener)
    client.start()
    if (!client.blockUntilConnected(config.zkTimeoutDuration.toMillis.toInt, TimeUnit.MILLISECONDS)) {
      // If we couldn't connect, throw any errors that were reported
      listener.lastError.foreach { e => throw e }
    }

    client.getUnhandledErrorListenable().removeListener(listener)
    client
  }

  private class LastErrorListener extends UnhandledErrorListener {
    private[this] var _lastError: Option[Throwable] = None
    override def unhandledError(message: String, e: Throwable): Unit = {
      _lastError = Some(e)
    }

    def lastError = _lastError
  }
}
