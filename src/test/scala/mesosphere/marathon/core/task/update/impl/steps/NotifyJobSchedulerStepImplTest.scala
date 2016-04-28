package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Provider
import mesosphere.marathon.core.jobs.JobScheduler
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.test.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Future

class NotifyJobSchedulerStepImplTest extends FunSuite with Matchers with GivenWhenThen with Mockito with ScalaFutures {
  test("name") {
    new Fixture().step.name should equal("notifyJobScheduler")
  }

  test("notifying job scheduler") {
    val f = new Fixture
    val expectedUpdate = TaskStatusUpdateTestHelper.running().wrapped

    Given("a status update")
    f.jobScheduler.notifyOfTaskChanged(expectedUpdate) returns Future.successful(())

    When("calling processUpdate")
    f.step.processUpdate(expectedUpdate).futureValue

    Then("the update is passed to the JobScheduler")
    verify(f.jobScheduler).notifyOfTaskChanged(expectedUpdate)
  }

  class Fixture {
    val jobScheduler = mock[JobScheduler]
    val jobSchedulerProvider = new Provider[JobScheduler] {
      override def get(): JobScheduler = jobScheduler
    }
    val step = new NotifyJobSchedulerStepImpl(jobSchedulerProvider)
  }
}
