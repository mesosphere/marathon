package mesosphere.marathon

import java.util.{ TimerTask, Timer }
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.{ TestKit, TestProbe }
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.{ Group, Candidate }
import com.twitter.common.zookeeper.Group.JoinException
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppRepository, Migration }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.util.FrameworkIdUtil
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.{ any, eq => mockEq }
import org.mockito.Mockito
import org.mockito.Mockito.{ verify, when }
import org.rogach.scallop.ScallopOption
import org.scalatest.BeforeAndAfterAll

object MarathonSchedulerServiceTest {
  import Mockito.mock

  val ReconciliationDelay = 5000L
  val ReconciliationInterval = 5000L

  def mockConfig = {
    val config = mock(classOf[MarathonConf])

    when(config.reconciliationInitialDelay).thenReturn(scallopOption(Some(ReconciliationDelay)))
    when(config.reconciliationInterval).thenReturn(scallopOption(Some(ReconciliationInterval)))

    config
  }

  def scallopOption[A](a: Option[A]): ScallopOption[A] = {
    new ScallopOption[A]("") {
      override def get = a
      override def apply() = a.get
    }
  }
}

class MarathonSchedulerServiceTest extends TestKit(ActorSystem("System")) with MarathonSpec with BeforeAndAfterAll {
  import MarathonSchedulerServiceTest._

  var probe: TestProbe = _
  var healthCheckManager: HealthCheckManager = _
  var candidate: Option[Candidate] = _
  var config: MarathonConf = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var leader: AtomicBoolean = _
  var appRepository: AppRepository = _
  var taskTracker: TaskTracker = _
  var scheduler: MarathonScheduler = _
  var migration: Migration = _
  var schedulerActor: ActorRef = _

  before {
    probe = TestProbe()
    healthCheckManager = mock[HealthCheckManager]
    candidate = mock[Option[Candidate]]
    config = mockConfig
    frameworkIdUtil = mock[FrameworkIdUtil]
    leader = mock[AtomicBoolean]
    appRepository = mock[AppRepository]
    taskTracker = mock[TaskTracker]
    scheduler = mock[MarathonScheduler]
    migration = mock[Migration]
    schedulerActor = probe.ref
  }

  test("Start timer when elected") {
    val timer = mock[Timer]

    when(frameworkIdUtil.fetch(any(), any())).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      scheduler,
      system,
      migration,
      schedulerActor
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
      override def newDriver() = mock[SchedulerDriver]
    }

    schedulerService.reconciliationTimer = timer

    schedulerService.onElected(mock[ExceptionalCommand[Group.JoinException]])

    verify(timer).schedule(any[TimerTask](), mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
    verify(timer).schedule(any(), mockEq(ReconciliationDelay + ReconciliationInterval))
  }

  test("Cancel timer when defeated") {
    val timer = mock[Timer]

    when(frameworkIdUtil.fetch(any(), any())).thenReturn(None)

    val schedulerService = new MarathonSchedulerService(
      healthCheckManager,
      candidate,
      config,
      frameworkIdUtil,
      leader,
      appRepository,
      taskTracker,
      scheduler,
      system,
      migration,
      schedulerActor
    ) {
      override def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = ()
      override def newDriver() = mock[SchedulerDriver]
    }

    schedulerService.reconciliationTimer = timer

    schedulerService.onDefeated()

    verify(timer).cancel()
    assert(schedulerService.reconciliationTimer != timer, "timer should be replaced after leadership defeat")
  }
}
