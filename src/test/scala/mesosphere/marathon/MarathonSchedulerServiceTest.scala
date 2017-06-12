package mesosphere.marathon

import java.util.{ Timer, TimerTask }

import akka.Done
import akka.actor.ActorRef
import akka.testkit.TestProbe
import mesosphere.AkkaFunTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.base.RichRuntime
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.migration.Migration
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import org.apache.mesos.{ SchedulerDriver, Protos => mesos }
import org.mockito.Matchers.{ eq => mockEq }
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.rogach.scallop.ScallopOption

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

    when(config.reconciliationInitialDelay).thenReturn(scallopOption(Some(ReconciliationDelay)))
    when(config.reconciliationInterval).thenReturn(scallopOption(Some(ReconciliationInterval)))
    when(config.scaleAppsInitialDelay).thenReturn(scallopOption(Some(ScaleAppsDelay)))
    when(config.scaleAppsInterval).thenReturn(scallopOption(Some(ScaleAppsInterval)))
    when(config.zkTimeoutDuration).thenReturn(1.second)
    when(config.maxActorStartupTime).thenReturn(scallopOption(Some(MaxActorStartupTime)))
    when(config.onElectedPrepareTimeout).thenReturn(scallopOption(Some(OnElectedPrepareTimeout)))

    config
  }

  def scallopOption[A](a: Option[A]): ScallopOption[A] = {
    new ScallopOption[A]("") {
      override def get = a
      override def apply() = a.get
    }
  }
}

class MarathonSchedulerServiceTest extends AkkaFunTest {
  import MarathonSchedulerServiceTest._

  private[this] var probe: TestProbe = _
  private[this] var heartbeatProbe: TestProbe = _
  private[this] var leadershipCoordinator: LeadershipCoordinator = _
  private[this] var healthCheckManager: HealthCheckManager = _
  private[this] var config: MarathonConf = _
  private[this] var httpConfig: HttpConf = _
  private[this] var frameworkIdRepository: FrameworkIdRepository = _
  private[this] var electionService: ElectionService = _
  private[this] var groupManager: GroupManager = _
  private[this] var taskTracker: InstanceTracker = _
  private[this] var marathonScheduler: MarathonScheduler = _
  private[this] var migration: Migration = _
  private[this] var schedulerActor: ActorRef = _
  private[this] var heartbeatActor: ActorRef = _
  private[this] var prePostDriverCallbacks: scala.collection.immutable.Seq[PrePostDriverCallback] = _
  private[this] var mockTimer: Timer = _

  before {
    probe = TestProbe()
    heartbeatProbe = TestProbe()
    leadershipCoordinator = mock[LeadershipCoordinator]
    healthCheckManager = mock[HealthCheckManager]
    config = mockConfig
    httpConfig = mock[HttpConf]
    frameworkIdRepository = mock[FrameworkIdRepository]
    electionService = mock[ElectionService]
    groupManager = mock[GroupManager]
    taskTracker = mock[InstanceTracker]
    marathonScheduler = mock[MarathonScheduler]
    migration = mock[Migration]
    schedulerActor = probe.ref
    heartbeatActor = heartbeatProbe.ref
    prePostDriverCallbacks = scala.collection.immutable.Seq.empty
    mockTimer = mock[Timer]
    groupManager.refreshGroupCache() returns Future.successful(Done)
  }

  def driverFactory[T](provide: => SchedulerDriver): SchedulerDriverFactory = {
    new SchedulerDriverFactory {
      override def createDriver(): SchedulerDriver = provide
    }
  }

  test("Start timer when elected") {
    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      config,
      electionService,
      prePostDriverCallbacks,
      groupManager,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      heartbeatActor
    )
    schedulerService.timer = mockTimer

    when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
    schedulerService.startLeadership()

    verify(mockTimer).schedule(any[TimerTask], mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
  }

  test("Cancel timer when defeated") {
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

  test("Exit on loss of leadership") {

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      config,
      electionService,
      prePostDriverCallbacks,
      groupManager,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
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

  test("throw in start leadership when migration fails") {

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      config,
      electionService,
      prePostDriverCallbacks,
      groupManager,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
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

  test("throw when the driver creation fails by some exception") {
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

  test("Abdicate leadership when driver ends with error") {
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

  test("Pre/post driver callbacks are called") {
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
