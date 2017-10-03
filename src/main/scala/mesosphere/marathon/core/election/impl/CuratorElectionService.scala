package mesosphere.marathon
package core.election.impl

import java.util
import java.util.Collections
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.{ CrashStrategy, ShutdownHooks }
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.metrics.Metrics
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.framework.{ AuthInfo, CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.{ CreateMode, KeeperException, ZooDefs }
import org.apache.zookeeper.data.ACL

import scala.async.Async
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * This class implements leader election using Curator and (in turn) Zookeeper. It is used
  * when the high-availability mode is enabled.
  *
  * One can become a leader only. If the leadership is lost due to some reason, it shuts down Marathon.
  * Marathon gets stopped on leader abdication too.
  */
class CuratorElectionService(
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

  private[this] lazy val client = createCuratorClient()
  private[this] val leaderLatch = new AtomicReference(Option.empty[LeaderLatch])

  override def isLeader: Boolean = isCurrentlyLeading.get

  override def leaderHostPort: Option[String] = leaderHostPortMetric {
    if (client.getState == CuratorFrameworkState.STOPPED) None
    else {
      try {
        leaderLatch.get.flatMap { latch =>
          val participant = latch.getLeader
          if (participant.isLeader) Some(participant.getId) else None
        }
      } catch {
        case NonFatal(ex) =>
          logger.error("Error while getting current leader", ex)
          None
      }
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
      require(leaderLatch.get.isEmpty, "leaderLatch is not empty")

      startCuratorClient()
      val latch = new LeaderLatch(
        client, config.zooKeeperLeaderPath + "-curator", hostPort)
      latch.addListener(LeaderChangeListener, threadExecutor)
      latch.start()
      leaderLatch.set(Some(latch))
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
      stopLeadership()
    } catch {
      case NonFatal(ex) =>
        logger.error("Fatal error while stopping", ex)
    } finally {
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
    leaderLatch.get.foreach { latch =>
      try {
        if (latch.getState == LeaderLatch.State.STARTED)
          latch.close()
      } catch {
        case NonFatal(ex) =>
          logger.error("Could not close the leader latch", ex)
      }
    }
    leaderLatch.set(None)

    if (client.getState == CuratorFrameworkState.STARTED) {
      try {
        client.close()
      } catch {
        case NonFatal(ex) =>
          logger.error("Could not close the curator client", ex)
      }
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

  private def createCuratorClient(): CuratorFramework = {
    logger.info(s"Will do leader election through ${config.zkHosts}")

    // let the world read the leadership information as some setups depend on that to find Marathon
    val defaultAcl = new util.ArrayList[ACL]()
    defaultAcl.addAll(config.zkDefaultCreationACL)
    defaultAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    val aclProvider = new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = defaultAcl
      override def getAclForPath(path: String): util.List[ACL] = defaultAcl
    }

    val retryBaseSleepTime = 1.second.toMillis.toInt
    val maxRetries = 10
    val retryPolicy = new ExponentialBackoffRetry(retryBaseSleepTime, maxRetries)

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

    client
  }

  private def startCuratorClient(): Unit = {
    client.start()
    client.blockUntilConnected(config.zkTimeoutDuration.toMillis.toInt, TimeUnit.MILLISECONDS)
  }

  /**
    * Listener which forwards leadership status events asynchronously via the provided function.
    *
    * We delegate the methods asynchronously so they are processed outside of the synchronized lock
    * for LeaderLatch.setLeadership
    */
  private object LeaderChangeListener extends LeaderLatchListener {
    override def notLeader(): Unit = Async.async {
      logger.info(s"Leader defeated. New leader: ${leaderHostPort.getOrElse("-")}")
      twitterCommonsTombstone.delete(onlyMyself = true)
      stop(exit = true)
    }

    override def isLeader(): Unit = Async.async {
      logger.info("Leader elected")
      leadershipAcquired()

      // write a tombstone into the old twitter commons leadership election path which always
      // wins the selection. Check that startLeadership was successful and didn't abdicate.
      if (CuratorElectionService.this.isLeader) {
        twitterCommonsTombstone.create()
      }
    }
  }

  private object twitterCommonsTombstone {
    def memberPath(member: String): String = {
      config.zooKeeperLeaderPath.stripSuffix("/") + "/" + member
    }

    // - precedes 0-9 in ASCII and hence this instance overrules other candidates
    lazy val memberName: String = "member_-00000000"
    lazy val path: String = memberPath(memberName)

    var fallbackCreated = false

    def create(): Unit = {
      try {
        delete(onlyMyself = false)

        client.createContainers(config.zooKeeperLeaderPath)

        // Create a ephemeral node which is not removed when loosing leadership. This is necessary to avoid a
        // race of old Marathon instances which think that they can become leader in the moment
        // the new instances fail-over and no tombstone is existing (yet).
        if (!fallbackCreated) {
          client.create().
            creatingParentsIfNeeded().
            withMode(CreateMode.EPHEMERAL_SEQUENTIAL).
            forPath(memberPath("member_-1"), hostPort.getBytes("UTF-8"))
          fallbackCreated = true
        }

        logger.info("Creating tombstone for old twitter commons leader election")
        client.create().
          creatingParentsIfNeeded().
          withMode(CreateMode.EPHEMERAL).
          forPath(path, hostPort.getBytes("UTF-8"))
      } catch {
        case NonFatal(e) =>
          logger.error(s"Exception while creating tombstone for twitter commons leader election: ${e.getMessage}")
          abdicateLeadership()
      }
    }

    @SuppressWarnings(Array("SwallowedException"))
    def delete(onlyMyself: Boolean): Unit = try {
      Option(client.checkExists().forPath(path)).foreach { tombstone =>
        try {
          if (!onlyMyself ||
            new String(client.getData.forPath(memberPath(memberName))) == hostPort) {
            logger.info("Deleting existing tombstone for old twitter commons leader election")
            client.delete().guaranteed().withVersion(tombstone.getVersion).forPath(path)
          }
        } catch {
          case _: KeeperException.NoNodeException =>
          case _: KeeperException.BadVersionException =>
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.error("Unexpected exception while deleting the old twitter Commons leader election tombstone; " +
          "Commit suicide", e)
        abdicateLeadership()
    }
  }
}
