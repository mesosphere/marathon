package mesosphere.marathon
package core.election

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.Cancellable
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.metrics.{Metrics, Timer}
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.util.{CancellableOnce, LifeCycledCloseableLike}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorWatcher
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.Watcher.Event.EventType
import org.apache.zookeeper.{KeeperException, WatchedEvent}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object CuratorElectionStream extends StrictLogging {

  /**
    * Connects to Zookeeper and offers leadership; monitors leader state. Watches for leadership changes (leader
    * changed, was elected leader, lost leadership), and emits events accordingly.
    *
    * Materialized cancellable is used to abdicate leadership; which will do so followed by a closing of the stream.
    *
    * Responsibilities:
    *
    * - Completes the stream if Cancellable is called
    * - Fails the stream if unhandled exception occurs
    * - Emit the current leader
    * - Emit uncertainty about leadership in the event of connection turbulence
    *
    * Non responsiblities:
    *
    * - Crash if we transition from leader / not leader (ElectionService handles this)
    * - Crash if the stream fails (ElectionService handles this)
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
        val client: CuratorFramework = clientCloseable.closeable
        val cancelled = new AtomicBoolean(false)
        val curatorLeaderLatchPath = zooKeeperLeaderPath + "-curator"
        val latch = new LeaderLatch(client, curatorLeaderLatchPath, hostPort)

        /**
          * On first invocation, synchronously close the latch.
          *
          * This allows us to remove our leadership offering before closing the zookeeper client.
          */
        lazy val closeHook: () => Unit = { () =>
          // logger.debug
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

          logger.info("Starting leader latch")
          latch.start()
          startLeaderEventsPoll(latch, metrics, singleThreadEC, client, curatorLeaderLatchPath, hostPort, sq)
        } catch {
          case ex: Throwable =>
            logger.error("Error starting curator leader latch")
            sq.fail(ex)
        }

        sq.watchCompletion().onComplete { result =>
          logger.info(s"Leader status update SourceQueue is completed with ${result}")
          closeHook()
        }(singleThreadEC)
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

    require(latch.getState == LeaderLatch.State.STARTED, "The leader latch must be started before calling this method.")
    val currentLoopId = new AtomicInteger()

    val leaderHostPortMetric: Timer =
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
     *
     * @param loopId The current loopId. Used as a cancellation mechanism which ensures only one instance of this loop is running.
     * @param retries Retry mechanism, used during first initialization of Marathon
     */
    def longPollLeaderChange(loopId: Int, retries: Int = 0): Unit = singleThreadEC.execute { () =>
      if (currentLoopId.get() != loopId) {
        logger.info(s"Leader watch loop ${loopId} cancelled")
      } else {
        try {
          logger.debug(s"Leader watch loop ${loopId} registering watch")
          client.getChildren
            .usingWatcher(new CuratorWatcher {
              override def process(event: WatchedEvent): Unit = {
                logger.debug(s"Leader watch loop ${loopId} event received: ${event}")
                if (event.getType != EventType.None) // ignore connection errors
                  longPollLeaderChange(loopId)
              }
            })
            .forPath(curatorLeaderLatchPath)
          emitLeader(loopId)
        } catch {
          case ex: KeeperException.NoNodeException if retries < 10 =>
            /**
              * During the first boot of Marathon, the parent node that we watch for ephemeral leader records will be
              * eventually created. There is no hook to which we can subscribe to be notified when this initial
              * registration is completed.
              *
              * We retry up to 10 times, increasing the delay between retries by 10ms per loop, waiting up to a total of
              * 450ms. If the background process does not succeed before that, we crash. (again, note, this only applies
              * to the first boot of Marathon, ever).
              *
              * This retry logic is not used for any other purpose (lost connections, etc.).
              */
            logger.info(s"Leader watch loop ${loopId} retrying; waiting for latch initialization.")
            Thread.sleep(retries * 10L)
            longPollLeaderChange(loopId, retries + 1)
          case ex: KeeperException.ConnectionLossException =>
            // Do nothing; we emit a None on loss already, and we'll start a new loop
            logger.info(s"Leader watch loop ${loopId} stopped due to connection loss", ex)
          case ex: Throwable =>
            logger.info(s"Leader watch loop ${loopId} failed", ex)
            // This will lead to ElectionService crashing (standby or not); it ends the stream
            sq.fail(ex)
        }
      }
    }

    /**
      * Emit current leader. Does not fail on connection error, but throws if multiple election candidates have the same
      * ID.
      */
    def emitLeader(loopId: Int): Unit = {
      val participants = leaderHostPortMetric.blocking {
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
      logger.debug(s"Leader watch loop ${loopId}: current leaders = ${participants.map(_.getId).mkString(", ")}")

      val selfParticipantCount = participants.iterator.count(_.getId == hostPort)
      if (selfParticipantCount > 1) {
        throw new IllegalStateException(s"Multiple election participants have the same id: ${hostPort}. This is not allowed.")
      } else {
        val nextState = participants.find(_.isLeader).map(_.getId) match {
          case Some(leader) if leader == hostPort => LeadershipState.ElectedAsLeader
          case otherwise => LeadershipState.Standby(otherwise)
        }
        sq.offer(nextState)
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
}
