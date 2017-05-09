package mesosphere.marathon
package core.matcher.reconcile.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.{ GroupCreation, MarathonTestHelper }

import scala.concurrent.Future

class OfferMatcherReconcilerTest extends UnitTest with GroupCreation {

  "OfferMatcherReconciler" should {
    "offer without reservations leads to no task ops" in {
      val f = new Fixture
      Given("an offer without reservations")
      val offer = MarathonTestHelper.makeBasicOffer().build()
      When("reconciling")
      val matchedTaskOps = f.reconciler.matchOffer(offer).futureValue
      Then("no task ops are generated")
      matchedTaskOps.ops should be(empty)
    }

    "offer with volume for unknown tasks/apps leads to unreserve/destroy" in {
      val f = new Fixture
      Given("an offer with volume")
      val appId = PathId("/test")
      val taskId = Task.Id.forRunSpec(appId)
      val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
      val offer = MarathonTestHelper.offerWithVolumes(taskId, localVolumeIdLaunched)

      And("no groups")
      f.groupRepository.root() returns Future.successful(createRootGroup())
      And("no tasks")
      f.taskTracker.instancesBySpec()(any) returns Future.successful(InstancesBySpec.empty)

      When("reconciling")
      val matchedTaskOps = f.reconciler.matchOffer(offer).futureValue

      Then("all resources are destroyed and unreserved")
      val expectedOps =
        Seq(
          InstanceOp.UnreserveAndDestroyVolumes(
            InstanceUpdateOperation.ForceExpunge(taskId.instanceId),
            oldInstance = None,
            resources = offer.getResourcesList.to[Seq]
          )
        )

      matchedTaskOps.ops should contain theSameElementsAs expectedOps
    }

    "offer with volume for unknown tasks leads to unreserve/destroy" in {
      val f = new Fixture
      Given("an offer with volume")
      val appId = PathId("/test")
      val taskId = Task.Id.forRunSpec(appId)
      val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
      val offer = MarathonTestHelper.offerWithVolumes(taskId, localVolumeIdLaunched)

      And("a bogus app")
      val app = AppDefinition(appId)
      f.groupRepository.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))
      And("no tasks")
      f.taskTracker.instancesBySpec()(any) returns Future.successful(InstancesBySpec.empty)

      When("reconciling")
      val matchedTaskOps = f.reconciler.matchOffer(offer).futureValue

      Then("all resources are destroyed and unreserved")
      val expectedOps = Seq(
        InstanceOp.UnreserveAndDestroyVolumes(
          InstanceUpdateOperation.ForceExpunge(taskId.instanceId),
          oldInstance = None,
          resources = offer.getResourcesList.to[Seq]
        )
      )

      matchedTaskOps.ops should contain theSameElementsAs expectedOps
    }

    "offer with volume for unknown apps leads to unreserve/destroy" in {
      val f = new Fixture
      Given("an offer with volume")
      val appId = PathId("/test")
      val taskId = Task.Id.forRunSpec(appId)
      val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
      val offer = MarathonTestHelper.offerWithVolumes(taskId, localVolumeIdLaunched)

      And("no groups")
      f.groupRepository.root() returns Future.successful(createRootGroup())
      And("a matching bogus instance")
      val bogusInstance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance().copy(instanceId = taskId.instanceId)
      f.taskTracker.instancesBySpec()(any) returns Future.successful(InstancesBySpec.forInstances(bogusInstance))

      When("reconciling")
      val matchedTaskOps = f.reconciler.matchOffer(offer).futureValue

      Then("all resources are destroyed and unreserved")
      val expectedOps = Seq(
        InstanceOp.UnreserveAndDestroyVolumes(
          InstanceUpdateOperation.ForceExpunge(taskId.instanceId),
          oldInstance = Some(bogusInstance),
          resources = offer.getResourcesList.to[Seq]
        )
      )

      matchedTaskOps.ops should contain theSameElementsAs expectedOps
    }

    "offer with volume for known tasks/apps DOES NOT lead to unreserve/destroy" in {
      val f = new Fixture
      Given("an offer with volume")
      val appId = PathId("/test")
      val taskId = Task.Id.forRunSpec(appId)
      val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
      val offer = MarathonTestHelper.offerWithVolumes(taskId, localVolumeIdLaunched)

      And("a matching bogus app")
      val app = AppDefinition(appId)
      f.groupRepository.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))
      And("a matching bogus task")
      f.taskTracker.instancesBySpec()(any) returns Future.successful(
        InstancesBySpec.forInstances(TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance().copy(instanceId = taskId.instanceId))
      )

      When("reconciling")
      val matchedTaskOps = f.reconciler.matchOffer(offer).futureValue

      Then("no resources are destroyed and unreserved")
      matchedTaskOps.ops should be(empty)
    }
  }
  class Fixture {
    lazy val taskTracker = mock[InstanceTracker]
    lazy val groupRepository = mock[GroupRepository]
    lazy val reconciler = new OfferMatcherReconciler(taskTracker, groupRepository)
  }
}
