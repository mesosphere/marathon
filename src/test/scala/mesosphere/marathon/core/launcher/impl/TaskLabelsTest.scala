package mesosphere.marathon
package core.launcher.impl

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

class TaskLabelsTest extends FunSuite with GivenWhenThen with Matchers {
  test("no labels => no taskId") {
    val f = new Fixture

    Given("unlabeled resources")
    When("checking for taskIds")
    val instanceIds = f.unlabeledResources.flatMap(TaskLabels.instanceIdForResource(f.frameworkId, _))

    Then("we don't get any instanceIds")
    instanceIds should be(empty)
  }

  test("correct labels => taskId") {
    val f = new Fixture

    Given("correctly labeled resources")
    When("checking for instanceIds")
    val instanceIds = f.labeledResources.flatMap(TaskLabels.instanceIdForResource(f.frameworkId, _))

    Then("we get as many instanceIds as resources")
    instanceIds should be(Seq.fill(f.labeledResources.size)(f.taskId.instanceId))
  }

  test("labels with incorrect frameworkId are ignored") {
    val f = new Fixture

    Given("labeled resources for other framework")
    When("checking for instanceIds")
    val instanceIds = f.labeledResourcesForOtherFramework.flatMap(TaskLabels.instanceIdForResource(f.frameworkId, _))

    Then("we don't get instanceIds")
    instanceIds should be(empty)

  }

  class Fixture {
    val appId = PathId("/test")
    val taskId = Task.Id.forRunSpec(appId)
    val frameworkId = MarathonTestHelper.frameworkId
    val otherFrameworkId = FrameworkId("very other different framework id")

    val unlabeledResources = MarathonTestHelper.makeBasicOffer().getResourcesList
    require(unlabeledResources.nonEmpty)
    require(unlabeledResources.forall(!_.hasReservation))

    def labelResourcesFor(frameworkId: FrameworkId): Seq[MesosProtos.Resource] = {
      MarathonTestHelper.makeBasicOffer(
        reservation = Some(TaskLabels.labelsForTask(frameworkId, taskId)),
        role = "test"
      ).getResourcesList.to[Seq]
    }

    val labeledResources = labelResourcesFor(frameworkId)
    require(labeledResources.nonEmpty)
    require(labeledResources.forall(_.hasReservation))

    val labeledResourcesForOtherFramework = labelResourcesFor(otherFrameworkId)
    require(labeledResourcesForOtherFramework.nonEmpty)
    require(labeledResourcesForOtherFramework.forall(_.hasReservation))
  }
}
