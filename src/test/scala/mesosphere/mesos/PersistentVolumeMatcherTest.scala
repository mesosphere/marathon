package mesosphere.mesos

import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon._
import mesosphere.marathon.stream._
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper, Mockito }
import org.scalatest.{ GivenWhenThen, Matchers }

class PersistentVolumeMatcherTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Missing volumes result in NO match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer without persistent volumes")
    val app = f.appWithPersistentVolume()
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(
      reservation = Task.Reservation(Seq(Task.LocalVolumeId(app.id, "persistent-volume", "uuid")), f.taskReservationStateNew))
      .getInstance()
    val instances: Seq[Instance] = Seq(instance)

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

    Then("We receive a None")
    matchOpt shouldBe empty
  }

  test("Correct available volumes result in a match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer with matching persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
    val instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(
      reservation = Task.Reservation(Seq(localVolumeId), f.taskReservationStateNew))
      .getInstance()

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

  test("Multiple correct available volumes for multiple tasks result in the correct task as a match") {
    val f = new Fixture

    Given("a resident app with 2 instances and an offer with 3 persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId1 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid1")
    val localVolumeId2 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid2")
    val localVolumeId3 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid3")
    val instances = IndexedSeq(
      TestInstanceBuilder.newBuilder(app.id).addTaskReserved(
        reservation = Task.Reservation(Seq(localVolumeId2), f.taskReservationStateNew))
        .getInstance(),
      TestInstanceBuilder.newBuilder(app.id).addTaskReserved(
        reservation = Task.Reservation(Seq(localVolumeId3), f.taskReservationStateNew))
        .getInstance()
    )
    val unknownInstance = TestInstanceBuilder.newBuilder(PathId("/unknown")).addTaskReserved(
      reservation = Task.Reservation(Seq(localVolumeId2), f.taskReservationStateNew))
      .getInstance()
    val offer =
      f.offerWithVolumes(unknownInstance, localVolumeId1)
        .toBuilder
        .addAllResources(MarathonTestHelper.persistentVolumeResources(instances.head.tasksMap.values.head.taskId, localVolumeId2))
        .addAllResources(MarathonTestHelper.persistentVolumeResources(instances(1).tasksMap.values.head.taskId, localVolumeId3))
        .build()

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

    Then("We receive a Match for the first task and the second offered volume")
    matchOpt should not be empty
    matchOpt.get.instance.instanceId shouldEqual instances.head.instanceId
    matchOpt.get.persistentVolumeResources should have size 1
    matchOpt.get.persistentVolumeResources.head shouldEqual offer.getResources(1)
  }

  test("Unwanted available volumes result in NO match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer with matching persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
    val instances = Seq(
      TestInstanceBuilder.newBuilder(app.id).addTaskReserved(
        reservation = Task.Reservation(
          Seq(Task.LocalVolumeId(app.id, "other-container", "uuid")), f.taskReservationStateNew))
        .getInstance())
    val offer = f.offerWithVolumes(instances.head, localVolumeId)

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, instances)

    Then("We receive a None")
    matchOpt shouldBe empty
  }

  class Fixture {
    def makeTask(appId: PathId, reservation: Task.Reservation) = TestTaskBuilder.Helper.minimalReservedTask(appId, reservation)
    def offerWithVolumes(instance: Instance, localVolumeIds: Task.LocalVolumeId*) = {
      val taskId = instance.tasksMap.values.head.taskId
      MarathonTestHelper.offerWithVolumesOnly(taskId, localVolumeIds: _*)
    }
    def appWithPersistentVolume(): AppDefinition = MarathonTestHelper.appWithPersistentVolume()
    val taskReservationStateNew = TestTaskBuilder.Helper.taskReservationStateNew
  }
}
