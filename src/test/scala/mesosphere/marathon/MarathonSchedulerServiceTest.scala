package mesosphere.marathon

import java.util.{ Timer, TimerTask }

import akka.Done
import akka.actor.ActorRef
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.base.RichRuntime
import mesosphere.marathon.core.deployment.DeploymentManager
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.migration.Migration
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.marathon.util.ScallopStub
import org.apache.mesos.{ SchedulerDriver, Protos => mesos }
import org.mockito.Matchers.{ eq => mockEq }
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.concurrent.Future
import scala.concurrent.duration._

object MarathonSchedulerServiceTest {
  import Mockito.mock

  val ReconciliationDelay = 5000L
  val ReconciliationInterval = 5000L
  val ScaleAppsDelay = 4000L
  val ScaleAppsInterval = 4000L
  val MaxActorStartupTime = 5000L
  val OnElectedPrepareTimeout = 3 * 60 * 1000L

  def mockConfig: MarathonConf = {
    val config = mock(classOf[MarathonConf])

    when(config.reconciliationInitialDelay).thenReturn(ScallopStub(Some(ReconciliationDelay)))
    when(config.reconciliationInterval).thenReturn(ScallopStub(Some(ReconciliationInterval)))
    when(config.scaleAppsInitialDelay).thenReturn(ScallopStub(Some(ScaleAppsDelay)))
    when(config.scaleAppsInterval).thenReturn(ScallopStub(Some(ScaleAppsInterval)))
    when(config.zkTimeoutDuration).thenReturn(1.second)
    when(config.maxActorStartupTime).thenReturn(ScallopStub(Some(MaxActorStartupTime)))
    when(config.onElectedPrepareTimeout).thenReturn(ScallopStub(Some(OnElectedPrepareTimeout)))

    config
  }
}

class MarathonSchedulerServiceTest extends AkkaUnitTest {
  import MarathonSchedulerServiceTest._

  case class Fixture() {
    val probe: TestProbe = TestProbe()
    val heartbeatProbe: TestProbe = TestProbe()
    val leadershipCoordinator: LeadershipCoordinator = mock[LeadershipCoordinator]
    val healthCheckManager: HealthCheckManager = mock[HealthCheckManager]
    val config: MarathonConf = mockConfig
    val httpConfig: HttpConf = mock[HttpConf]
    val frameworkIdRepository: FrameworkIdRepository = mock[FrameworkIdRepository]
    val electionService: ElectionService = mock[ElectionService]
    val groupManager: GroupManager = mock[GroupManager]
    val taskTracker: InstanceTracker = mock[InstanceTracker]
    val marathonScheduler: MarathonScheduler = mock[MarathonScheduler]
    val migration: Migration = mock[Migration]
    val schedulerActor: ActorRef = probe.ref
    val heartbeatActor: ActorRef = heartbeatProbe.ref
    val prePostDriverCallbacks: Seq[PrePostDriverCallback] = Seq.empty
    val mockTimer: Timer = mock[Timer]
    val deploymentManager: DeploymentManager = mock[DeploymentManager]

    groupManager.invalidateGroupCache() returns Future.successful(Done)
  }

  def driverFactory[T](provide: => SchedulerDriver): SchedulerDriverFactory = {
    new SchedulerDriverFactory {
      override def createDriver(): SchedulerDriver = provide
    }
  }

  "MarathonSchedulerService" should {
    "Start timer when elected" in new Fixture {
      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        prePostDriverCallbacks,
        groupManager,
        driverFactory(mock[SchedulerDriver]),
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor
      )
      schedulerService.timer = mockTimer

      when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
      schedulerService.startLeadership()

      verify(mockTimer).schedule(any[TimerTask], mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
    }

    "Cancel timer when defeated" in new Fixture {
      val driver = mock[SchedulerDriver]
      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        prePostDriverCallbacks,
        groupManager,
        driverFactory(driver),
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor
      ) {
        override def startLeadership(): Unit = ()
      }

      schedulerService.timer = mockTimer
      schedulerService.driver = Some(driver)
      schedulerService.stopLeadership()

      verify(mockTimer).cancel()
      assert(schedulerService.timer != mockTimer, "Timer should be replaced after leadership defeat")
      val hmsg = heartbeatProbe.expectMsgType[Heartbeat.Message]
      assert(Heartbeat.MessageDeactivate(MesosHeartbeatMonitor.sessionOf(driver)) == hmsg)
    }

    "Exit on loss of leadership" in new Fixture {

      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        prePostDriverCallbacks,
        groupManager,
        driverFactory(mock[SchedulerDriver]),
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor) {
        override def newTimer() = mockTimer
      }

      schedulerService.timer = mockTimer

      when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))

      schedulerService.startLeadership()

      schedulerService.stopLeadership()

      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }

    "throw in start leadership when migration fails" in new Fixture {

      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        prePostDriverCallbacks,
        groupManager,
        driverFactory(mock[SchedulerDriver]),
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor
      )
      schedulerService.timer = mockTimer

      import java.util.concurrent.TimeoutException

      // use an Answer object here because Mockito's thenThrow does only
      // allow to throw RuntimeExceptions
      when(migration.migrate()).thenAnswer(new Answer[StorageVersion] {
        override def answer(invocation: InvocationOnMock): StorageVersion = {
          throw new TimeoutException("Failed to wait for future within timeout")
        }
      })

      intercept[TimeoutException] {
        schedulerService.startLeadership()
      }
    }

    "throw when the driver creation fails by some exception" in new Fixture {
      val driverFactory = mock[SchedulerDriverFactory]

      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        prePostDriverCallbacks,
        groupManager,
        driverFactory,
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor
      )

      schedulerService.timer = mockTimer

      when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
      when(driverFactory.createDriver()).thenThrow(new Exception("Some weird exception"))

      intercept[Exception] {
        schedulerService.startLeadership()
      }
    }

    "Abdicate leadership when driver ends with error" in new Fixture {
      val driver = mock[SchedulerDriver]
      val driverFactory = mock[SchedulerDriverFactory]

      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        prePostDriverCallbacks,
        groupManager,
        driverFactory,
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor
      )
      schedulerService.timer = mockTimer

      when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
      when(driverFactory.createDriver()).thenReturn(driver)

      when(driver.run()).thenThrow(new RuntimeException("driver failure"))

      schedulerService.startLeadership()
      verify(electionService, Mockito.timeout(1000)).abdicateLeadership(error = true, reoffer = true)
    }

    "Pre/post driver callbacks are called" in new Fixture {
      val cb = mock[PrePostDriverCallback]
      Mockito.when(cb.postDriverTerminates).thenReturn(Future(()))
      Mockito.when(cb.preDriverStarts).thenReturn(Future(()))

      val driver = mock[SchedulerDriver]
      val driverFactory = mock[SchedulerDriverFactory]

      val schedulerService = new MarathonSchedulerService(
        leadershipCoordinator,
        config,
        electionService,
        scala.collection.immutable.Seq(cb),
        groupManager,
        driverFactory,
        system,
        migration,
        deploymentManager,
        schedulerActor,
        heartbeatActor
      )
      schedulerService.timer = mockTimer

      when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
      when(driverFactory.createDriver()).thenReturn(driver)

      val driverCompleted = new java.util.concurrent.CountDownLatch(1)
      when(driver.run()).thenAnswer(new Answer[mesos.Status] {
        override def answer(invocation: InvocationOnMock): mesos.Status = {
          driverCompleted.await()
          mesos.Status.DRIVER_RUNNING
        }
      })

      schedulerService.startLeadership()

      val startOrder = Mockito.inOrder(migration, cb, driver)
      awaitAssert(startOrder.verify(migration).migrate())
      awaitAssert(startOrder.verify(cb).preDriverStarts)
      awaitAssert(startOrder.verify(driver).run())

      schedulerService.stopLeadership()
      awaitAssert(verify(driver).stop(true))

      driverCompleted.countDown()
      awaitAssert(verify(cb).postDriverTerminates)

      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }
  }
}
