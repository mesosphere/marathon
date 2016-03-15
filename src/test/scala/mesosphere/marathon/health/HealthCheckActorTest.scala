package mesosphere.marathon.health

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon._
import mesosphere.util.CallerThreadExecutionContext
import org.apache.mesos.Protos.TaskID
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
    val tracker = mock[TaskTracker]
    val latch = TestLatch(1)

    val appId = "/test".toPath
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId)
    val appRepository: AppRepository = mock[AppRepository]
    when(appRepository.app(appId, appVersion)).thenReturn(Future.successful(Some(app)))

    val task = MarathonTestHelper.stagedTask("test_task.9876543", appVersion = appVersion)

    when(tracker.appTasksSync(appId)).thenReturn(Set(task))

    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    val actor = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(app, holder, HealthCheck(), tracker, system.eventStream) {
          override val workerProps = Props {
            latch.countDown()
            new TestActors.EchoActor
          }
        }
      )
    )

    actor.underlyingActor.dispatchJobs()

    latch.isOpen should be (false)
  }

  // regression test for #1456
  test("task should be killed if health check fails") {
    val tracker: TaskTracker = mock[TaskTracker]
    val scheduler: MarathonScheduler = mock[MarathonScheduler]
    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    val driver = mock[SchedulerDriver]
    holder.driver = Some(driver)

    val appId = "test".toPath
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId)
    val appRepository: AppRepository = mock[AppRepository]
    when(appRepository.app(appId, appVersion)).thenReturn(Future.successful(Some(app)))

    val task = MarathonTestHelper.runningTask("test_task.9876543", appVersion = appVersion)

    val healthCheck: HealthCheck = HealthCheck(maxConsecutiveFailures = 3)

    val actor = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(app, holder, healthCheck, tracker, system.eventStream)
      )
    )

    actor.underlyingActor.checkConsecutiveFailures(task, Health(task.taskId, consecutiveFailures = 3))

    verify(driver).killTask(task.taskId.mesosTaskId)

    verifyNoMoreInteractions(tracker, driver, scheduler)
  }
}

