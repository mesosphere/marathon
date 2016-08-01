package mesosphere.marathon.core.health.impl

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import mesosphere.marathon._
import mesosphere.marathon.core.health.{ Health, HealthCheck }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Timestamp }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.util.CallerThreadExecutionContext
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ verify, verifyNoMoreInteractions, when }
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.collection.immutable.Set
import scala.concurrent.Future

class HealthCheckActorTest
    extends MarathonActorSupport
    with MarathonSpec with Matchers with BeforeAndAfterAll {

  override lazy implicit val system: ActorSystem =
    ActorSystem(
      name = "system",
      defaultExecutionContext = Some(CallerThreadExecutionContext.callerThreadExecutionContext)
    )

  // regression test for #934
  test("should not dispatch health checks for staging tasks") {
    val f = new Fixture
    val latch = TestLatch(1)
    val appId = "/test".toPath
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId)
    val appRepository: AppRepository = mock[AppRepository]

    when(appRepository.app(appId, appVersion)).thenReturn(Future.successful(Some(app)))

    when(f.tracker.appTasksSync(f.appId)).thenReturn(Set(f.task))

    val actor = f.actorWithLatch(HealthCheck(maxConsecutiveFailures = 3), latch)
    actor.underlyingActor.dispatchJobs()
    latch.isOpen should be (false)
    verifyNoMoreInteractions(f.driver)
  }

  test("should not dispatch health checks for lost tasks") {
    val f = new Fixture
    val latch = TestLatch(1)
    when(f.tracker.appTasksSync(f.appId)).thenReturn(Set(f.lostTask))

    val actor = f.actorWithLatch(HealthCheck(maxConsecutiveFailures = 3), latch)

    actor.underlyingActor.dispatchJobs()
    latch.isOpen should be (false)
    verifyNoMoreInteractions(f.driver)
  }

  test("should not dispatch health checks for unreachable tasks") {
    val f = new Fixture
    val latch = TestLatch(1)
    when(f.tracker.appTasksSync(f.appId)).thenReturn(Set(f.unreachableTask))

    val actor = f.actorWithLatch(HealthCheck(maxConsecutiveFailures = 3), latch)

    actor.underlyingActor.dispatchJobs()
    latch.isOpen should be (false)
    verifyNoMoreInteractions(f.driver)
  }

  // regression test for #1456
  test("task should be killed if health check fails") {
    val f = new Fixture
    val actor = f.actor(HealthCheck(maxConsecutiveFailures = 3))

    actor.underlyingActor.checkConsecutiveFailures(f.task, Health(f.task.taskId, consecutiveFailures = 3))
    verify(f.killService).killTask(f.task, TaskKillReason.FailedHealthChecks)
    verifyNoMoreInteractions(f.tracker, f.driver, f.scheduler)
  }

  test("task should not be killed if health check fails, but the task is unreachable") {
    val f = new Fixture
    val actor = f.actor(HealthCheck(maxConsecutiveFailures = 3))

    actor.underlyingActor.checkConsecutiveFailures(f.unreachableTask, Health(f.unreachableTask.taskId, consecutiveFailures = 3))
    verifyNoMoreInteractions(f.tracker, f.driver, f.scheduler)
  }

  class Fixture {
    val tracker = mock[TaskTracker]

    val appId = "/test".toPath
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId)
    val appRepository: AppRepository = mock[AppRepository]
    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    val driver = mock[SchedulerDriver]
    holder.driver = Some(driver)
    val killService: TaskKillService = mock[TaskKillService]
    when(appRepository.app(appId, appVersion)).thenReturn(Future.successful(Some(app)))

    val taskId = "test_task.9876543"
    val scheduler: MarathonScheduler = mock[MarathonScheduler]

    val task = MarathonTestHelper.runningTask("test_task.9876543", appVersion = appVersion)
    val lostTask = MarathonTestHelper.mininimalLostTask(appId)
    val unreachableTask = MarathonTestHelper.minimalUnreachableTask(appId)

    def actor(healthCheck: HealthCheck) = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(app, killService, healthCheck, tracker, system.eventStream)
      )
    )

    def actorWithLatch(healthCheck: HealthCheck, latch: TestLatch) = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(app, killService, HealthCheck(), tracker, system.eventStream) {
          override val workerProps = Props {
            latch.countDown()
            new TestActors.EchoActor
          }
        }
      )
    )
  }
}

