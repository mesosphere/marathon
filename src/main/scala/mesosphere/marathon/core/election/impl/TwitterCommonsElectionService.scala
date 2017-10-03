package mesosphere.marathon
package core.election.impl

import java.util
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ ExecutorService, Executors }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.twitter.common.base.{ ExceptionalCommand, Supplier }
import com.twitter.common.quantity.{ Amount, Time }
import com.twitter.common.zookeeper.Group.JoinException
import com.twitter.common.zookeeper.{ Candidate, Group, ZooKeeperClient }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.{ CrashStrategy, ShutdownHooks }
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.metrics.Metrics
import org.apache.zookeeper.data.ACL
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooDefs, ZooKeeper }

import scala.async.Async
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

class TwitterCommonsElectionService(
  config: MarathonConf,
  hostPort: String,
  system: ActorSystem,
  override val eventStream: EventStream,
  override val metrics: Metrics,
  shutdownHooks: ShutdownHooks,
  crashStrategy: CrashStrategy)
    extends ElectionService with ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  system.registerOnTermination {
    logger.info("Stopping leadership on shutdown")
    stop(exit = false)
  }

  private[this] val exitTimeoutOnAbdication: FiniteDuration = 500.milliseconds

  private[this] val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  /** We re-use the single thread executor here because some methods of this class might get blocked for a long time. */
  private[this] implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadExecutor)

  private[this] val currentCandidate = new AtomicReference(Option.empty[ElectionCandidate])
  private[this] val isCurrentlyLeading = new AtomicBoolean(false)

  // These variables are initialized with `false` and can only be set to `true` later.
  private[this] val leadershipOffered = new AtomicBoolean(false)
  private[this] val acquiringLeadership = new AtomicBoolean(false)

  override def isLeader: Boolean = isCurrentlyLeading.get

  override def leaderHostPort: Option[String] = leaderHostPortMetric {
    val maybeLeaderData: Option[Array[Byte]] = try {
      Option(candidate.getLeaderData.orNull())
    } catch {
      case NonFatal(e) =>
        logger.error("Error while getting the current leader", e)
        None
    }
    maybeLeaderData.map { data =>
      new String(data, "UTF-8")
    }
  }

  override def offerLeadership(candidate: ElectionCandidate): Unit = {
    logger.info(s"$candidate offered leadership")
    if (leadershipOffered.compareAndSet(false, true)) {
      if (!shutdownHooks.isShuttingDown) {
        logger.info("Going to acquire leadership")
        currentCandidate.set(Some(candidate))
        Async.async {
          try {
            acquireLeadership()
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Fatal error while acquiring leadership for $candidate. Exiting now", ex)
              stop(exit = true)
          }
        }
      } else {
        logger.info("Not accepting the leadership offer since Marathon is shutting down")
      }
    } else {
      logger.error(s"Got another leadership offer from $candidate. Exiting now")
      stop(exit = true)
    }
  }

  private def acquireLeadership(): Unit = {
    if (acquiringLeadership.compareAndSet(false, true)) {
      candidate.offerLeadership(Leader)
    } else {
      logger.error("Acquiring leadership in parallel to someone else. Exiting now")
      stop(exit = true)
    }
  }

  private def leadershipAcquired(): Unit = {
    currentCandidate.get match {
      case Some(_) =>
        try {
          startLeadership()
          isCurrentlyLeading.set(true)
        } catch {
          case NonFatal(ex) =>
            logger.error(s"Fatal error while starting leadership of $currentCandidate. Exiting now", ex)
            stop(exit = true)
        }
      case _ =>
        logger.error("Leadership is already acquired. Exiting now")
        stop(exit = true)
    }
  }

  override def abdicateLeadership(): Unit = {
    logger.info("Abdicating leadership")
    stop(exit = true, exitTimeout = exitTimeoutOnAbdication)
  }

  private def stop(exit: Boolean, exitTimeout: FiniteDuration = 0.milliseconds): Unit = {
    logger.info("Stopping the election service")
    isCurrentlyLeading.set(false)
    try {
      abdicateCommand.get.foreach(_.execute())
      stopLeadership()
    } catch {
      case NonFatal(ex) =>
        logger.error("Fatal error while stopping", ex)
    } finally {
      abdicateCommand.set(None)
      currentCandidate.set(None)
      if (exit) {
        logger.info("Terminating due to leadership abdication or failure")
        system.scheduler.scheduleOnce(exitTimeout) {
          crashStrategy.crash()
        }
      }
    }
  }

  private def startLeadership(): Unit = {
    currentCandidate.get.foreach(startCandidateLeadership)
    startMetrics()
  }

  private def stopLeadership(): Unit = {
    try {
      client.close()
    } catch {
      case NonFatal(ex) =>
        logger.error("Could not close the ZooKeeper client", ex)
    }

    stopMetrics()
    currentCandidate.get.foreach(stopCandidateLeadership)
  }

  private[this] val candidateLeadershipStarted = new AtomicBoolean(false)
  private def startCandidateLeadership(candidate: ElectionCandidate): Unit = {
    if (candidateLeadershipStarted.compareAndSet(false, true)) {
      logger.info(s"Starting $candidate's leadership")
      candidate.startLeadership()
      logger.info(s"Started $candidate's leadership")
      eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)
    }
  }

  private def stopCandidateLeadership(candidate: ElectionCandidate): Unit = {
    if (candidateLeadershipStarted.compareAndSet(true, false)) {
      logger.info(s"Stopping $candidate's leadership")
      candidate.stopLeadership()
      logger.info(s"Stopped $candidate's leadership")
      eventStream.publish(LocalLeadershipEvent.Standby)
    }
  }

  private lazy val client: ZooKeeperClient = createZooKeeperClient()
  private lazy val candidate: Candidate = createCandidate(client)
  private lazy val abdicateOnConnectionLoss: AbdicateOnConnectionLoss = new AbdicateOnConnectionLoss(client, this)
  private val abdicateCommand: AtomicReference[Option[ExceptionalCommand[JoinException]]] =
    new AtomicReference(None)

  private def createZooKeeperClient(): ZooKeeperClient = {
    require(config.zooKeeperSessionTimeout() < Integer.MAX_VALUE, "ZooKeeper timeout is too large")

    val sessionTimeout = Amount.of(config.zooKeeperSessionTimeout().toInt, Time.MILLISECONDS)
    val zooKeeperServers = config.zooKeeperHostAddresses
    val client = (config.zkUsername, config.zkPassword) match {
      case (Some(user), Some(pass)) =>
        new ZooKeeperClient(sessionTimeout, ZooKeeperClient.digestCredentials(user, pass), zooKeeperServers.asJava)
      case _ =>
        new ZooKeeperClient(sessionTimeout, zooKeeperServers.asJava)
    }

    // Marathon can't do anything useful without a ZK connection
    // so we wait to proceed until one is available
    var connectedToZk = false

    while (!connectedToZk) {
      try {
        logger.info("Connecting to ZooKeeper...")
        client.get
        connectedToZk = true
      } catch {
        case NonFatal(_) =>
          logger.warn("Unable to connect to ZooKeeper, retrying...")
      }
    }

    client
  }

  private def createCandidate(zk: ZooKeeperClient): Candidate = {
    logger.info(s"Registering in ZooKeeper with hostPort: $hostPort")

    // let the world read the leadership information as some setups depend on that to find Marathon
    lazy val acl = new util.ArrayList[ACL]()
    acl.addAll(config.zkDefaultCreationACL)
    acl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    new mesosphere.marathon.core.election.impl.CandidateImpl(
      new Group(zk, acl, config.zooKeeperLeaderPath),
      new Supplier[Array[Byte]] {
        def get(): Array[Byte] = {
          hostPort.getBytes("UTF-8")
        }
      }
    )
  }

  private object Leader extends Candidate.Leader {
    override def onDefeated(): Unit = {
      logger.info("Defeated (Leader Interface)")
      abdicateOnConnectionLoss.stop()
      abdicateLeadership()
    }

    override def onElected(abdicateCmd: ExceptionalCommand[JoinException]): Unit = {
      logger.info("Elected (Leader Interface)")
      abdicateCommand.set(Option(abdicateCmd))
      leadershipAcquired()
      abdicateOnConnectionLoss.start()
    }
  }

  private class AbdicateOnConnectionLoss(
      zk: ZooKeeperClient,
      electionService: ElectionService) {
    private val connectionDropped = Set(
      Watcher.Event.KeeperState.Disconnected,
      Watcher.Event.KeeperState.Expired
    )

    private[impl] val watcher = new Watcher {
      override def process(event: WatchedEvent): Unit = {
        if (connectionDropped.contains(event.getState)) {
          logger.info(s"ZooKeeper connection has been dropped. Abdicate Leadership: $event")
          electionService.abdicateLeadership()
        }
      }
    }

    def start(): Unit = {
      logger.info("Register as ZK Listener")
      zk.register(watcher)

      // make sure, we are connected so we can act on subsequent events
      try {
        if (zk.get().getState != ZooKeeper.States.CONNECTED)
          electionService.abdicateLeadership()
      } catch {
        case NonFatal(e) =>
          logger.error("Error whily retrieving a ZK connection's state", e)
          electionService.abdicateLeadership()
      }
    }

    def stop(): Unit = {
      logger.info("Unregister as ZK Listener")
      zk.unregister(watcher)
    }
  }
}
