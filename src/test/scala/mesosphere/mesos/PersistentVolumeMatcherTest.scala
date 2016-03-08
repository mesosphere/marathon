package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class PersistentVolumeMatcherTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Non-Resident app results in Match") {
    val f = new Fixture

    Given("a normal app without residency and an offer without persistent volumes")
    val app = MarathonTestHelper.makeBasicApp()
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val tasks = Seq(f.makeTask(app.id))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a VolumeMatch with an empty list")
    matchOpt should not be empty
    matchOpt.get.persistentVolumeResources shouldBe empty
  }

  test("Missing volumes result in NO match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer without persistent volumes")
    val app = f.appWithPersistentVolume()
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val tasks = Seq(f.makeTask(app.id,
      Task.Reservation(Seq(Task.LocalVolumeId(app.id, "persistent-volume", "uuid")), f.taskReservationStateNew)))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a None")
    matchOpt shouldBe empty
  }

  test("Correct available volumes result in a match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer with matching persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
    val offer = f.offerWithVolumes(localVolumeId)
    val tasks = Seq(f.makeTask(app.id, Task.Reservation(Seq(localVolumeId), f.taskReservationStateNew)))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a Match")
    matchOpt should not be empty
    matchOpt.get.task.taskId shouldEqual tasks.head.taskId
    matchOpt.get.persistentVolumeResources should have size 1
    matchOpt.get.persistentVolumeResources.head shouldEqual offer.getResources(0)
  }

  test("Multiple correct available volumes for multiple tasks result in the correct task as a match") {
    val f = new Fixture

    Given("a resident app with 2 tasks and an offer with 3 persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId1 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid1")
    val localVolumeId2 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid2")
    val localVolumeId3 = Task.LocalVolumeId(app.id, "persistent-volume", "uuid3")
    val offer = f.offerWithVolumes(localVolumeId1, localVolumeId2, localVolumeId3)
    val tasks = Seq(
      f.makeTask(app.id, Task.Reservation(Seq(localVolumeId2), f.taskReservationStateNew)),
      f.makeTask(app.id, Task.Reservation(Seq(localVolumeId3), f.taskReservationStateNew))
    )

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a Match for the first task and the second offered volume")
    matchOpt should not be empty
    matchOpt.get.task.taskId shouldEqual tasks.head.taskId
    matchOpt.get.persistentVolumeResources should have size 1
    matchOpt.get.persistentVolumeResources.head shouldEqual offer.getResources(1)
  }

  test("Unwanted available volumes result in NO match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer with matching persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
    val offer = f.offerWithVolumes(localVolumeId)
    val tasks = Seq(f.makeTask(app.id, Task.Reservation(
      Seq(Task.LocalVolumeId(app.id, "other-container", "uuid")), f.taskReservationStateNew)))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a None")
    matchOpt shouldBe empty
  }

  class Fixture {
    def makeTask(appId: PathId) = MarathonTestHelper.mininimalTask(appId)
    def makeTask(appId: PathId, reservation: Task.Reservation) = MarathonTestHelper.minimalReservedTask(appId, reservation)
    def offerWithVolumes(localVolumeIds: Task.LocalVolumeId*) = MarathonTestHelper.offerWithVolumesOnly(localVolumeIds: _*)
    def appWithPersistentVolume(): AppDefinition = MarathonTestHelper.appWithPersistentVolume()
    val taskReservationStateNew = MarathonTestHelper.taskReservationStateNew
  }
}
