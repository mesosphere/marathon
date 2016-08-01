package mesosphere.marathon

import java.util.{ Timer, TimerTask }

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.codahale.metrics.MetricRegistry
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppRepository, MarathonStore, Migration }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.util.state.memory.InMemoryStore
import mesosphere.util.state.{ FrameworkId, FrameworkIdUtil }
import org.apache.mesos.{ SchedulerDriver, Protos => mesos }
import org.mockito.Matchers.{ any, eq => mockEq }
import org.mockito.Mockito
import org.mockito.Mockito.{ times, verify, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.rogach.scallop.ScallopOption
import org.scalatest.{ BeforeAndAfterAll, Matchers }

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

class MarathonSchedulerServiceTest
    extends MarathonActorSupport
    with MarathonSpec
    with BeforeAndAfterAll
    with Matchers {
  import MarathonSchedulerServiceTest._

  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] var probe: TestProbe = _
  private[this] var heartbeatProbe: TestProbe = _
  private[this] var leadershipCoordinator: LeadershipCoordinator = _
  private[this] var healthCheckManager: HealthCheckManager = _
  private[this] var config: MarathonConf = _
  private[this] var httpConfig: HttpConf = _
  private[this] var frameworkIdUtil: FrameworkIdUtil = _
  private[this] var electionService: ElectionService = _
  private[this] var appRepository: AppRepository = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var scheduler: MarathonScheduler = _
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
    frameworkIdUtil = mock[FrameworkIdUtil]
    electionService = mock[ElectionService]
    appRepository = mock[AppRepository]
    taskTracker = mock[TaskTracker]
    scheduler = mock[MarathonScheduler]
    migration = mock[Migration]
    schedulerActor = probe.ref
    heartbeatActor = heartbeatProbe.ref
    prePostDriverCallbacks = scala.collection.immutable.Seq.empty
    mockTimer = mock[Timer]
  }

  def driverFactory[T](provide: => SchedulerDriver): SchedulerDriverFactory = {
    new SchedulerDriverFactory {
      override def createDriver(): SchedulerDriver = provide
    }
  }

  test("Start timer when elected") {
    when(frameworkIdUtil.fetch()).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      heartbeatActor
    )
    schedulerService.timer = mockTimer

    when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
    schedulerService.startLeadership()

    verify(mockTimer).schedule(any[TimerTask](), mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
  }

  test("Cancel timer when defeated") {
    when(frameworkIdUtil.fetch()).thenReturn(None)

    val driver = mock[SchedulerDriver]
    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
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

  test("Re-enable timer when re-elected") {
    when(frameworkIdUtil.fetch()).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      heartbeatActor,
      metrics = new Metrics(new MetricRegistry)
    ) {
      override def newTimer() = mockTimer
    }

    schedulerService.timer = mockTimer

    when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))

    schedulerService.startLeadership()

    schedulerService.stopLeadership()

    schedulerService.startLeadership()

    verify(mockTimer, times(2)).schedule(any(), mockEq(ScaleAppsDelay), mockEq(ScaleAppsInterval))
    verify(mockTimer, times(2)).schedule(any[TimerTask](), mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
    verify(mockTimer).cancel()
  }

  test("Always fetch current framework ID") {
    val frameworkId = mesos.FrameworkID.newBuilder.setValue("myId").build()
    val metrics = new Metrics(new MetricRegistry)
    val store = new MarathonStore[FrameworkId](new InMemoryStore, metrics, () => new FrameworkId(""), "frameworkId:")
    frameworkIdUtil = new FrameworkIdUtil(store, Duration.Inf)

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      heartbeatActor
    ) {
      override def startLeadership(): Unit = ()
      override def newTimer() = mockTimer
    }

    schedulerService.timer = mockTimer

    schedulerService.frameworkId should be(None)

    implicit lazy val timeout = 1.second
    frameworkIdUtil.store(frameworkId)

    awaitAssert(schedulerService.frameworkId should be(Some(frameworkId)))
  }

  test("Abdicate leadership when migration fails and reoffer leadership") {
    when(frameworkIdUtil.fetch()).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
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

    try {
      schedulerService.startLeadership()
    } catch {
      case _: TimeoutException =>
        schedulerService.stopLeadership()
    }

    verify(electionService, Mockito.timeout(1000)).offerLeadership(candidate = schedulerService)
  }

  test("Abdicate leadership when the driver creation fails by some exception") {
    when(frameworkIdUtil.fetch()).thenReturn(None)
    val driverFactory = mock[SchedulerDriverFactory]

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
      driverFactory,
      system,
      migration,
      schedulerActor,
      heartbeatActor
    )

    schedulerService.timer = mockTimer

    when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
    when(driverFactory.createDriver()).thenThrow(new Exception("Some weird exception"))

    try {
      schedulerService.startLeadership()
    } catch {
      case e: Exception => schedulerService.stopLeadership()
    }

    verify(electionService, Mockito.timeout(1000)).offerLeadership(candidate = schedulerService)
  }

  test("Abdicate leadership when driver ends with error") {
    when(frameworkIdUtil.fetch()).thenReturn(None)
    val driver = mock[SchedulerDriver]
    val driverFactory = mock[SchedulerDriverFactory]

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      prePostDriverCallbacks,
      appRepository,
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

    when(frameworkIdUtil.fetch()).thenReturn(None)
    val driver = mock[SchedulerDriver]
    val driverFactory = mock[SchedulerDriverFactory]

    val schedulerService = new MarathonSchedulerService(
      leadershipCoordinator,
      healthCheckManager,
      config,
      frameworkIdUtil,
      electionService,
      scala.collection.immutable.Seq(cb),
      appRepository,
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

    awaitAssert(verify(electionService).offerLeadership(candidate = schedulerService))
  }
}
