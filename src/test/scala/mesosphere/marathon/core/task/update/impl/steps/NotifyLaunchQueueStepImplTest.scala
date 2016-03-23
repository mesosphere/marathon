package mesosphere.marathon.core.task.update.impl.steps

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
    val f = new Fixture
    val expectedUpdate = TaskStatusUpdateTestHelper.running().wrapped

    Given("a status update")
    f.launchQueue.notifyOfTaskUpdate(expectedUpdate) returns Future.successful(None)

    When("calling processUpdate")
    f.step.processUpdate(expectedUpdate).futureValue

    Then("the update is passed to the LaunchQueue")
    verify(f.launchQueue).notifyOfTaskUpdate(expectedUpdate)
  }

  class Fixture {
    val launchQueue = mock[LaunchQueue]
    val launchQueueProvider = new Provider[LaunchQueue] {
      override def get(): LaunchQueue = launchQueue
    }
    val step = new NotifyLaunchQueueStepImpl(launchQueueProvider = launchQueueProvider)
  }
}
