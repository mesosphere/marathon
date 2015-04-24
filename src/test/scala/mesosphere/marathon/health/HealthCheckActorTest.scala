package mesosphere.marathon.health

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import mesosphere.marathon.health.HealthCheckActorTest.SameThreadExecutionContext
import mesosphere.marathon.{ MarathonScheduler, MarathonSchedulerDriverHolder, Protos, MarathonSpec }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import org.mockito.Mockito.when
import org.scalatest.Matchers

import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext

class HealthCheckActorTest extends TestKit(ActorSystem(name = "system", defaultExecutionContext = Some(SameThreadExecutionContext))) with MarathonSpec with Matchers {

  // regression test for #934
  test("should not dispatch health checks for staging tasks") {
    val tracker = mock[TaskTracker]
    val latch = TestLatch(1)
    val appId = "test".toPath
    val taskVersion = "1"

    val task = Protos.MarathonTask
      .newBuilder
      .setId("test_task.9876543")
      .setVersion(taskVersion)
      .build()

    when(tracker.get(appId)).thenReturn(Set(task))

    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    val actor = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(appId, taskVersion, holder, mock[MarathonScheduler], HealthCheck(), tracker, system.eventStream) {
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
