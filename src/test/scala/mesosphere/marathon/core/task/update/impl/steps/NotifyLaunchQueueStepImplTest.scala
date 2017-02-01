package mesosphere.marathon
package core.task.update.impl.steps

import akka.Done
import com.google.inject.Provider
import mesosphere.UnitTest
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper

import scala.concurrent.Future

class NotifyLaunchQueueStepImplTest extends UnitTest {
  "NotifyLaunchQueueStepImpl" should {
    "name" in {
      new Fixture().step.name should equal("notifyLaunchQueue")
    }

    "notifying launch queue" in {
      Given("a status update")
      val f = new Fixture
      val expectedUpdate = TaskStatusUpdateTestHelper.running().wrapped

      When("calling processUpdate")
      f.launchQueue.notifyOfInstanceUpdate(expectedUpdate) returns Future.successful(Done)
      f.step.process(expectedUpdate).futureValue

      Then("the update is passed to the LaunchQueue")
      verify(f.launchQueue).notifyOfInstanceUpdate(expectedUpdate)
    }
  }

  class Fixture {
    val launchQueue = mock[LaunchQueue]
    val launchQueueProvider = new Provider[LaunchQueue] {
      override def get(): LaunchQueue = launchQueue
    }
    val step = new NotifyLaunchQueueStepImpl(launchQueueProvider = launchQueueProvider)
  }
}
