package mesosphere.marathon

import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ Timer, TimerTask }

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import akka.testkit.{ TestKit, TestProbe }
import com.codahale.metrics.MetricRegistry
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import com.twitter.common.zookeeper.{ Candidate, Group }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ MarathonStore, AppRepository, Migration }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.util.state.{ FrameworkId, FrameworkIdUtil }
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.{ Protos => mesos, SchedulerDriver }
import org.mockito.Matchers.{ any, eq => mockEq }
import org.mockito.Mockito
import org.mockito.Mockito.{ times, verify, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.rogach.scallop.ScallopOption
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._

object MarathonSchedulerServiceTest {
  import Mockito.mock

  val ReconciliationDelay = 5000L
  val ReconciliationInterval = 5000L
  val ScaleAppsDelay = 4000L
  val ScaleAppsInterval = 4000L

  def mockConfig = {
    val config = mock(classOf[MarathonConf])

    when(config.reconciliationInitialDelay).thenReturn(scallopOption(Some(ReconciliationDelay)))
    when(config.reconciliationInterval).thenReturn(scallopOption(Some(ReconciliationInterval)))
    when(config.scaleAppsInitialDelay).thenReturn(scallopOption(Some(ScaleAppsDelay)))
    when(config.scaleAppsInterval).thenReturn(scallopOption(Some(ScaleAppsInterval)))
    when(config.zkTimeoutDuration).thenReturn(1.second)

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
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with BeforeAndAfterAll
    with Matchers {
  import MarathonSchedulerServiceTest._
  import system.dispatcher

  var probe: TestProbe = _
  var healthCheckManager: HealthCheckManager = _
  var candidate: Option[Candidate] = _
  var config: MarathonConf = _
  var httpConfig: HttpConf = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var leader: AtomicBoolean = _
  var appRepository: AppRepository = _
  var taskTracker: TaskTracker = _
  var scheduler: MarathonScheduler = _
  var migration: Migration = _
  var schedulerActor: ActorRef = _
  var events: EventStream = _

  before {
    probe = TestProbe()
    healthCheckManager = mock[HealthCheckManager]
    candidate = mock[Option[Candidate]]
    config = mockConfig
    httpConfig = mock[HttpConf]
    frameworkIdUtil = mock[FrameworkIdUtil]
    leader = mock[AtomicBoolean]
    appRepository = mock[AppRepository]
    taskTracker = mock[TaskTracker]
    scheduler = mock[MarathonScheduler]
    migration = mock[Migration]
    schedulerActor = probe.ref
    events = new EventStream()
  }

  def driverFactory[T](provide: => SchedulerDriver): SchedulerDriverFactory = {
    new SchedulerDriverFactory {
      override def createDriver(): SchedulerDriver = provide
    }
  }

  test("Start timer when elected") {
    val mockTimer = mock[Timer]

    when(frameworkIdUtil.fetch()).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      events
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
    }

    schedulerService.timer = mockTimer

    schedulerService.onElected(mock[ExceptionalCommand[Group.JoinException]])

    verify(mockTimer).schedule(any[TimerTask](), mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
    verify(mockTimer).schedule(any(), mockEq(ReconciliationDelay + ReconciliationInterval))
  }

  test("Cancel timer when defeated") {
    val mockTimer = mock[Timer]

    when(frameworkIdUtil.fetch()).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      events
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
    }

    schedulerService.timer = mockTimer

    schedulerService.onDefeated()

    verify(mockTimer).cancel()
    assert(schedulerService.timer != mockTimer, "Timer should be replaced after leadership defeat")
  }

  test("Re-enable timer when re-elected") {
    val mockTimer = mock[Timer]

    when(frameworkIdUtil.fetch()).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      events
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
      override def newTimer() = mockTimer
    }

    schedulerService.onElected(mock[ExceptionalCommand[Group.JoinException]])

    schedulerService.onDefeated()

    schedulerService.onElected(mock[ExceptionalCommand[Group.JoinException]])

    verify(mockTimer, times(2)).schedule(any(), mockEq(ScaleAppsDelay), mockEq(ScaleAppsInterval))
    verify(mockTimer, times(2)).schedule(any[TimerTask](), mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
    verify(mockTimer, times(2)).schedule(any(), mockEq(ReconciliationDelay + ReconciliationInterval))
    verify(mockTimer).cancel()
  }

  test("Always fetch current framework ID") {
    val frameworkId = mesos.FrameworkID.newBuilder.setValue("myId").build()
    val mockTimer = mock[Timer]

    val metrics = new Metrics(new MetricRegistry)
    val store = new MarathonStore[FrameworkId](new InMemoryStore, metrics, () => new FrameworkId(""))
    frameworkIdUtil = new FrameworkIdUtil(store, Duration.Inf)

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      events
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
      override def newTimer() = mockTimer
    }

    schedulerService.frameworkId should be(None)

    implicit lazy val timeout = 1.second
    frameworkIdUtil.store(frameworkId)

    awaitAssert(schedulerService.frameworkId should be(Some(frameworkId)))
  }

  test("Abdicate leadership when migration fails and reoffer leadership") {
    when(frameworkIdUtil.fetch()).thenReturn(None)
    candidate = Some(mock[Candidate])

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      driverFactory(mock[SchedulerDriver]),
      system,
      migration,
      schedulerActor,
      events
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
    }

    // use an Answer object here because Mockito's thenThrow does only
    // allow to throw RuntimeExceptions
    when(migration.migrate()).thenAnswer(new Answer[StorageVersion] {
      override def answer(invocation: InvocationOnMock): StorageVersion = {
        import java.util.concurrent.TimeoutException
        throw new TimeoutException("Failed to wait for future within timeout")
      }
    })

    schedulerService.onElected(mock[ExceptionalCommand[Group.JoinException]])

    awaitAssert { verify(candidate.get).offerLeadership(schedulerService) }
    leader.get() should be (false)
  }

  test("Abdicate leadership when the driver creation fails by some exception") {
    when(frameworkIdUtil.fetch()).thenReturn(None)
    candidate = Some(mock[Candidate])
    val driverFactory = mock[SchedulerDriverFactory]

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      driverFactory,
      system,
      migration,
      schedulerActor,
      events
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
    }

    when(driverFactory.createDriver()).thenThrow(new Exception("Some weird exception"))

    schedulerService.onElected(mock[ExceptionalCommand[Group.JoinException]])

    awaitAssert { verify(candidate.get).offerLeadership(schedulerService) }
    leader.get() should be (false)
  }
}
