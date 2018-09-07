package mesosphere.marathon
package core.election

import akka.actor.Cancellable
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.OverflowStrategy
import com.typesafe.scalalogging.StrictLogging
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import mesosphere.marathon.metrics.{Metrics, Timer}
import mesosphere.marathon.metrics.deprecated.ServiceMetric
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.util.{CancellableOnce, LifeCycledCloseableLike}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{ACLProvider, CuratorWatcher, UnhandledErrorListener}
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.Watcher.Event.EventType
import org.apache.zookeeper.{KeeperException, WatchedEvent, ZooDefs}
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object CuratorElectionStream extends StrictLogging {

  /**
    * Connects to Zookeeper and offers leadership; monitors leader state. Watches for leadership changes (leader
    * changed, was elected leader, lost leadership), and emits events accordingly.
    *
    * Materialized cancellable is used to abdicate leadership; which will do so followed by a closing of the stream.
    */
  def apply(
    metrics: Metrics,
    clientCloseable: LifeCycledCloseableLike[CuratorFramework],
    zooKeeperLeaderPath: String,
    zooKeeperConnectionTimeout: FiniteDuration,
    hostPort: String,
    singleThreadEC: ExecutionContext): Source[LeadershipState, Cancellable] = {
    leaderLatchStream(metrics, clientCloseable, zooKeeperLeaderPath, hostPort, singleThreadEC)
      .initialTimeout(zooKeeperConnectionTimeout)
      .concat(Source.single(LeadershipState.Standby(None)))
      .via(EnrichedFlow.dedup(LeadershipState.Standby(None)))
      .map { e =>
        e match {
          case LeadershipState.ElectedAsLeader =>
            logger.info(s"Leader won: ${hostPort}")
          case LeadershipState.Standby(None) =>
            logger.info("Leader unknown.")
          case LeadershipState.Standby(Some(currentLeader)) =>
            logger.info(s"Leader defeated. Current leader: ${currentLeader}")
        }
        e
      }
  }

  private def leaderLatchStream(
    metrics: Metrics,
    clientCloseable: LifeCycledCloseableLike[CuratorFramework],
    zooKeeperLeaderPath: String,
    hostPort: String,
    singleThreadEC: ExecutionContext): Source[LeadershipState, Cancellable] = {

    Source.queue[LeadershipState](16, OverflowStrategy.dropHead)
      .mapMaterializedValue { sq =>
        val client = clientCloseable.closeable
        val cancelled = new AtomicBoolean(false)
        val curatorLeaderLatchPath = zooKeeperLeaderPath + "-curator"
        val latch = new LeaderLatch(client, curatorLeaderLatchPath, hostPort)

        /**
          * On first invocation, synchronously close the latch.
          *
          * This allows us to remove our leadership offering before closing the zookeeper client.
          */
        lazy val closeHook: () => Unit = { () =>
          if (cancelled.compareAndSet(false, true)) {
            clientCloseable.removeBeforeClose(closeHook)
            try {
              logger.info("Closing leader latch")
              latch.close()
              logger.info("Leader latch closed")
            } catch {
              case ex: Throwable =>
                logger.error("Error closing CuratorElectionStream latch", ex)
            }
            sq.complete()
          }
        }

        try {
          /* We register the beforeClose hook to ensure that we have an opportunity to remove the latch entry before we
           * lose our connection to Zookeeper */
          clientCloseable.beforeClose(closeHook)

          logger.info("starting leader latch")
          latch.start()
          startLeaderEventsPoll(latch, metrics, singleThreadEC, client, curatorLeaderLatchPath, hostPort, sq)
        } catch {
          case ex: Throwable =>
            logger.error("Error starting curator leader latch")
            sq.fail(ex)
        }

        sq.watchCompletion().onComplete { _ => closeHook() }(singleThreadEC)
        new CancellableOnce(closeHook)
      }
  }

  private def startLeaderEventsPoll(
    latch: LeaderLatch,
    metrics: Metrics,
    singleThreadEC: ExecutionContext,
    client: CuratorFramework,
    curatorLeaderLatchPath: String,
    hostPort: String,
    sq: SourceQueueWithComplete[LeadershipState]): Unit = {

    require(latch.getState == LeaderLatch.State.STARTED)
    val currentLoopId = new AtomicInteger()

    val oldLeaderHostPortMetric: Timer =
      metrics.deprecatedTimer(ServiceMetric, getClass, "current-leader-host-port")
    val newLeaderHostPortMetric: Timer =
      metrics.timer("debug.current-leader.retrieval.duration")

    /* Long-poll trampoline-style recursive method which calls emitLeader() each time it detects that the leadership
     * state has changed.
     *
     * Given instance A, B, C, Curator's Leader latch recipe only provides A the ability to be notified if it gains or
     * loses leadership, but not if leadership transitions between B and C. This method allows us to monitor any change
     * in leadership state.
     *
     * It is important that we re-register our watch _BEFORE_ we get the current leader. In Zookeeper,
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
     *
     * We also have a retry and (very simple) back-off mechanism. This is because Curator's leader latch creates the
     * initial leader node asynchronously. If we poll for leader information before this background hook completes, then
     * a KeeperException.NoNodeException is thrown (which we handle, and retry)
     */
    def longPollLeaderChange(id: Int, retries: Int = 0): Unit = singleThreadEC.execute { () =>
      if (currentLoopId.get() != id) {
        logger.info(s"Leader watch loop ${id} cancelled")
      } else {
        try {
          logger.debug(s"Leader watch loop ${id} registering watch")
          client.getChildren
            .usingWatcher(new CuratorWatcher {
              override def process(event: WatchedEvent): Unit = {
                logger.debug(s"Leader watch loop ${id} event received: ${event}")
                if (event.getType != EventType.None) // ignore connection errors
                  longPollLeaderChange(id)
              }
            })
            .forPath(curatorLeaderLatchPath)
          emitLeader(id)
        } catch {
          case ex: KeeperException.NoNodeException if retries < 10 =>
            // Wait for node to be created
            logger.info(s"Leader watch loop ${id} retrying; waiting for latch initialization.")
            Thread.sleep(retries * 10L)
            longPollLeaderChange(id, retries + 1)
          case ex: KeeperException.ConnectionLossException =>
            logger.info(s"Leader watch loop ${id} stopped due to connection loss", ex)
          // Do nothing; we emit a None on loss already, and we'll start a new loop
          case ex: Throwable =>
            logger.info(s"Leader watch loop ${id} failed", ex)
            sq.fail(ex)
        }
      }
    }

    /**
      * Emit current leader. Does not fail on connection error, but throws if multiple election candidates have the same
      * ID.
      */
    def emitLeader(id: Int): Unit = {
      val participants = oldLeaderHostPortMetric.blocking {
        newLeaderHostPortMetric.blocking {
          try {
            if (client.getState == CuratorFrameworkState.STOPPED)
              Nil
            else
              latch.getParticipants.asScala.toList
          } catch {
            case ex: Throwable =>
              logger.error("Error while getting current leader", ex)
              Nil
          }
        }
      }
      logger.debug(s"Leader watch loop ${id}: current leaders = ${participants.map(_.getId).mkString(", ")}")

      val selfParticipantCount = participants.iterator.count(_.getId == hostPort)
      if (selfParticipantCount > 1) {
        throw new IllegalStateException(s"Multiple election participants have the same id: ${hostPort}. This is not allowed.")
      } else {
        val element = participants.find(_.isLeader).map(_.getId) match {
          case Some(leader) if leader == hostPort => LeadershipState.ElectedAsLeader
          case otherwise => LeadershipState.Standby(otherwise)
        }
        sq.offer(element)
      }
    }

    /**
      * Monitor connection loss events and emit leadership uncertainty
      *
      * Also, begin a new watch loop during each reconnect, as watches will be dropped during a session expiry (and
      * potentially other buggy cases?). This is in alignment with how LeaderLatch behaves.
      */
    val leaderEmitterListener: ConnectionStateListener = new ConnectionStateListener {
      def stateChanged(client: CuratorFramework, state: ConnectionState): Unit = state match {
        case ConnectionState.RECONNECTED =>
          // prevent spawning off another loop if source queue is completed.
          if (!sq.watchCompletion().isCompleted) {
            val nextLoopId = currentLoopId.incrementAndGet()
            logger.info(s"Connection reestablished; spawning new leader watch loop ${nextLoopId}")
            longPollLeaderChange(nextLoopId)
          }
        case ConnectionState.LOST | ConnectionState.SUSPENDED =>
          logger.info(s"Connection ${state}; assuming leader is unknown")
          sq.offer(LeadershipState.Standby(None)) // https://cwiki.apache.org/confluence/display/CURATOR/TN14
        case otherwise =>
          logger.debug(s"Connection State ${otherwise}")
      }
    }

    client.getConnectionStateListenable.addListener(leaderEmitterListener)
    longPollLeaderChange(currentLoopId.incrementAndGet())
    sq.watchCompletion().onComplete {
      case _ =>
        client.getConnectionStateListenable.removeListener(leaderEmitterListener)
        currentLoopId.incrementAndGet() // cancel poll loop
    }(singleThreadEC)
  }

  def newCuratorConnection(zkUrl: ZookeeperConf.ZkUrl, sessionTimeoutMs: Int, connectionTimeoutMs: Int,
    timeoutDurationMs: Int, defaultCreationACL: util.ArrayList[ACL]) = {
    logger.info(s"Will do leader election through ${zkUrl.redactedConnectionString}")

    // let the world read the leadership information as some setups depend on that to find Marathon
    val defaultAcl = new util.ArrayList[ACL]()
    defaultAcl.addAll(defaultCreationACL)
    defaultAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    val aclProvider = new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = defaultAcl
      override def getAclForPath(path: String): util.List[ACL] = defaultAcl
    }

    val retryPolicy = new ExponentialBackoffRetry(1.second.toMillis.toInt, 3)
    val builder = CuratorFrameworkFactory.builder().
      connectString(zkUrl.hostsString).
      sessionTimeoutMs(sessionTimeoutMs).
      connectionTimeoutMs(connectionTimeoutMs).
      aclProvider(aclProvider).
      retryPolicy(retryPolicy)

    // optionally authenticate
    zkUrl.credentials.foreach { credentials =>
      builder.authorization(Collections.singletonList(credentials.authInfoDigest))
    }
    val client = builder.build()

    val listener = new LastErrorListener
    client.getUnhandledErrorListenable().addListener(listener)
    client.start()
    if (!client.blockUntilConnected(timeoutDurationMs, TimeUnit.MILLISECONDS)) {
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
