package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.util.state.FrameworkId
import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import org.apache.mesos.{ Protos => MesosProtos }

class TaskLabelsTest extends FunSuite with GivenWhenThen with Matchers {
  test("no labels => no taskId") {
    val f = new Fixture

    Given("unlabeled resources")
    When("checking for taskIds")
    val taskIds = f.unlabeledResources.flatMap(TaskLabels.taskIdForResource(f.frameworkId, _))

    Then("we don't get any taskIds")
    taskIds should be(empty)
  }

  test("correct labels => taskId") {
    val f = new Fixture

    Given("correctly labeled resources")
    When("checking for taskIds")
    val taskIds = f.labeledResources.flatMap(TaskLabels.taskIdForResource(f.frameworkId, _))

    Then("we get as many taskIds as resources")
    taskIds should be(Iterable.fill(f.labeledResources.size)(f.taskId))
  }

  test("labels with incorrect frameworkId are ignored") {
    val f = new Fixture

    Given("labeled resources for other framework")
    When("checking for taskIds")
    val taskIds = f.labeledResourcesForOtherFramework.flatMap(TaskLabels.taskIdForResource(f.frameworkId, _))

    Then("we don't get task ids")
    taskIds should be(empty)
  }

  class Fixture {
    import scala.collection.JavaConverters._

    val appId = PathId("/test")
    val taskId = Task.Id.forApp(appId)
    val frameworkId = MarathonTestHelper.frameworkId
    val otherFrameworkId = FrameworkId("very other different framework id")

    val unlabeledResources = MarathonTestHelper.makeBasicOffer().getResourcesList.asScala
    require(unlabeledResources.nonEmpty)
    require(unlabeledResources.forall(!_.hasReservation))

    def labelResourcesFor(frameworkId: FrameworkId): Iterable[MesosProtos.Resource] = {
      MarathonTestHelper.makeBasicOffer(
        reservation = Some(TaskLabels.labelsForTask(frameworkId, taskId)),
        role = "test"
      ).getResourcesList.asScala
    }

    val labeledResources = labelResourcesFor(frameworkId)
    require(labeledResources.nonEmpty)
    require(labeledResources.forall(_.hasReservation))

    val labeledResourcesForOtherFramework = labelResourcesFor(otherFrameworkId)
    require(labeledResourcesForOtherFramework.nonEmpty)
    require(labeledResourcesForOtherFramework.forall(_.hasReservation))
  }
}
