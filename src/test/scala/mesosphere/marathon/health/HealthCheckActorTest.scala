package mesosphere.marathon.health

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import mesosphere.marathon.health.HealthCheckActorTest.SameThreadExecutionContext
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.{ TaskTracker, TaskTrackerImpl }
import mesosphere.marathon.{ MarathonScheduler, MarathonSchedulerDriverHolder, MarathonSpec, Protos }
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ verify, verifyNoMoreInteractions, when }
import org.scalatest.Matchers

import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext

class HealthCheckActorTest extends TestKit(ActorSystem(name = "system", defaultExecutionContext = Some(SameThreadExecutionContext))) with MarathonSpec with Matchers {

  // regression test for #934
  test("should not dispatch health checks for staging tasks") {
    val tracker = mock[TaskTracker]
    val latch = TestLatch(1)
    val appId = "test".toPath
    val appVersion = "1"

    val task = Protos.MarathonTask
      .newBuilder
      .setId("test_task.9876543")
      .setVersion(appVersion)
      .build()

    when(tracker.getTasks(appId)).thenReturn(Set(task))

    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    val actor = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(appId, appVersion, holder, mock[MarathonScheduler], HealthCheck(), tracker, system.eventStream) {
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
    val appVersion = "1"

    val task = Protos.MarathonTask
      .newBuilder
      .setId("test_task.9876543")
      .setVersion(appVersion)
      .build()

    val healthCheck: HealthCheck = HealthCheck(maxConsecutiveFailures = 3)

    val actor = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(
          appId, appVersion,
          holder, scheduler, healthCheck, tracker,
          system.eventStream
        )
      )
    )

    actor.underlyingActor.checkConsecutiveFailures(task, Health(task.getVersion, consecutiveFailures = 3))

    verify(driver).killTask(TaskID.newBuilder().setValue(task.getId).build())

    verifyNoMoreInteractions(tracker, driver, scheduler)
  }
}

object HealthCheckActorTest {
  object SameThreadExecutionContext extends ExecutionContext {
    override def execute(runnable: Runnable): Unit = {
      runnable.run()
    }

    override def reportFailure(cause: Throwable): Unit = {

    }
  }
}
