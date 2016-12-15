package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import com.google.inject.Provider
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.test.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Future

class NotifyLaunchQueueStepImplTest extends FunSuite with Matchers with GivenWhenThen with Mockito with ScalaFutures {
  test("name") {
    new Fixture().step.name should equal("notifyLaunchQueue")
  }

  test("notifying launch queue") {
    Given("a status update")
    val f = new Fixture
    val expectedUpdate = TaskStatusUpdateTestHelper.running().wrapped

    When("calling processUpdate")
    f.launchQueue.notifyOfInstanceUpdate(expectedUpdate) returns Future.successful(Done)
    f.step.process(expectedUpdate).futureValue

    Then("the update is passed to the LaunchQueue")
    verify(f.launchQueue).notifyOfInstanceUpdate(expectedUpdate)
  }

  class Fixture {
    val launchQueue = mock[LaunchQueue]
    val launchQueueProvider = new Provider[LaunchQueue] {
      override def get(): LaunchQueue = launchQueue
    }
    val step = new NotifyLaunchQueueStepImpl(launchQueueProvider = launchQueueProvider)
  }
}
