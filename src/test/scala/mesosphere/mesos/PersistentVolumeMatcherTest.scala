package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon._
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper

class PersistentVolumeMatcherTest extends UnitTest {
  "PersistentVolumeMatcher" should {
    "Missing volumes result in NO match" in {
      val f = new Fixture

      Given("a resident app with persistent volumes and an offer without persistent volumes")
      val app = f.appWithPersistentVolume()
      val offer = MarathonTestHelper.makeBasicOffer().build()

      TestInstanceBuilder.newBuilder(app.id).addTaskReserved()
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(
        Task.LocalVolumeId(app.id, "persistent-volume", "uuid"))
        .getInstance()
      val instances: Seq[Instance] = Seq(instance)

      When("We ask for a volume match")
      val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

      Then("We receive a None")
      matchOpt shouldBe empty
    }

    "Correct available volumes result in a match" in {
      val f = new Fixture

      Given("a resident app with persistent volumes and an offer with matching persistent volumes")
      val app = f.appWithPersistentVolume()
      val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(localVolumeId).getInstance()

      val instances: Seq[Instance] = Seq(instance)
      val offer = f.offerWithVolumes(instances.head, localVolumeId)

      When("We ask for a volume match")
      val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

      Then("We receive a Match")
      matchOpt should not be empty
      matchOpt.get.instance.instanceId shouldEqual instances.head.instanceId
      matchOpt.get.persistentVolumeResources should have size 1
      matchOpt.get.persistentVolumeResources.head shouldEqual offer.getResources(0)
    }

    "Multiple correct available volumes for multiple tasks result in the correct task as a match" in {
      val f = new Fixture

      Given("a resident app with 2 instances and an offer with 3 persistent volumes")
      val app = f.appWithPersistentVolume()
      val localVolumeId1 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid1")
      val localVolumeId2 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid2")
      val localVolumeId3 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid3")
      val instances = IndexedSeq(
        TestInstanceBuilder.newBuilder(app.id).addTaskReserved(localVolumeId2).getInstance(),
        TestInstanceBuilder.newBuilder(app.id).addTaskReserved(localVolumeId3).getInstance()
      )
      val unknownInstance = TestInstanceBuilder.newBuilder(PathId("/unknown")).addTaskReserved(localVolumeId2).getInstance()
      val offer =
        f.offerWithVolumes(unknownInstance, localVolumeId1)
          .toBuilder
          .addAllResources(MarathonTestHelper.persistentVolumeResources(instances.head.appTask.taskId, localVolumeId2).asJava)
          .addAllResources(MarathonTestHelper.persistentVolumeResources(instances(1).appTask.taskId, localVolumeId3).asJava)
          .build()

      When("We ask for a volume match")
      val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

      Then("We receive a Match for the first task and the second offered volume")
      matchOpt should not be empty
      matchOpt.get.instance.instanceId shouldEqual instances.head.instanceId
      matchOpt.get.persistentVolumeResources should have size 1
      matchOpt.get.persistentVolumeResources.head shouldEqual offer.getResources(1)
    }

    "Unwanted available volumes result in NO match" in {
      val f = new Fixture

      Given("a resident app with persistent volumes and an offer with matching persistent volumes")
      val app = f.appWithPersistentVolume()
      val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
      val instances = Seq(
        TestInstanceBuilder.newBuilder(app.id).addTaskReserved(Task.LocalVolumeId(app.id, "other-container", "uuid")).getInstance())
      val offer = f.offerWithVolumes(instances.head, localVolumeId)

      When("We ask for a volume match")
      val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

      Then("We receive a None")
      matchOpt shouldBe empty
    }
  }
  class Fixture {
    def offerWithVolumes(instance: Instance, localVolumeIds: Task.LocalVolumeId*) = {
      val taskId = instance.appTask.taskId
      MarathonTestHelper.offerWithVolumesOnly(taskId, localVolumeIds: _*)
    }
    def appWithPersistentVolume(): AppDefinition = MarathonTestHelper.appWithPersistentVolume()
  }
}
