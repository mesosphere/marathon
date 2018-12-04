package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon._
import mesosphere.marathon.core.instance.{Instance, LocalVolumeId, Reservation, TestInstanceBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper

class PersistentVolumeMatcherTest extends UnitTest {
  "PersistentVolumeMatcher" should {
    "Missing volumes result in NO match" in {
      val f = new Fixture

      Given("a resident app with persistent volumes and an offer without persistent volumes")
      val app = f.appWithPersistentVolume()
      val offer = MarathonTestHelper.makeBasicOffer().build()

      val instance = TestInstanceBuilder.scheduledWithReservation(app, Seq(LocalVolumeId(app.id, "persistent-volume", "uuid")))
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
      val localVolumeId = LocalVolumeId(app.id, "persistent-volume", "uuid")
      val instance = TestInstanceBuilder.scheduledWithReservation(app, Seq(localVolumeId))

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
      val localVolumeId1 = LocalVolumeId(app.id, "persistent-volume", "uuid1")
      val localVolumeId2 = LocalVolumeId(app.id, "persistent-volume", "uuid2")
      val localVolumeId3 = LocalVolumeId(app.id, "persistent-volume", "uuid3")
      val instances = IndexedSeq(
        TestInstanceBuilder.scheduledWithReservation(app, Seq(localVolumeId2)),
        TestInstanceBuilder.scheduledWithReservation(app, Seq(localVolumeId3))
      )
      val unknownInstance = TestInstanceBuilder.scheduledWithReservation(app, Seq(localVolumeId2))
      val offer =
        f.offerWithVolumes(unknownInstance, localVolumeId1)
          .toBuilder
          .addAllResources(MarathonTestHelper.persistentVolumeResources(Reservation.Id(instances.head.instanceId), localVolumeId2).asJava)
          .addAllResources(MarathonTestHelper.persistentVolumeResources(Reservation.Id(instances(1).instanceId), localVolumeId3).asJava)
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
      val localVolumeId = LocalVolumeId(app.id, "persistent-volume", "uuid")
      val instances = Seq(TestInstanceBuilder.scheduledWithReservation(app, Seq(LocalVolumeId(app.id, "other-container", "uuid"))))
      val offer = f.offerWithVolumes(instances.head, localVolumeId)

      When("We ask for a volume match")
      val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

      Then("We receive a None")
      matchOpt shouldBe empty
    }
  }
  class Fixture {
    def offerWithVolumes(instance: Instance, localVolumeIds: LocalVolumeId*) = {
      val taskId = Task.Id(instance.instanceId)
      MarathonTestHelper.offerWithVolumesOnly(taskId, localVolumeIds: _*)
    }
    def appWithPersistentVolume(): AppDefinition = MarathonTestHelper.appWithPersistentVolume()
  }
}
