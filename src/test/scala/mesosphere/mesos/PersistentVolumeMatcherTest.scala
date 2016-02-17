package mesosphere.mesos

import mesosphere.marathon.core.task.Task.{ ReservationWithVolumes, LocalVolumeId }
import mesosphere.marathon.state.{ Residency, AppDefinition, Container, PathId, PersistentVolume, PersistentVolumeInfo, Volume }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.PersistentVolumeMatcher.VolumeMatch
import org.apache.mesos.{ Protos => Mesos }
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
    val tasks = Seq(f.makeTask(app.id).copy(
      reservationWithVolumes = Some(ReservationWithVolumes(Seq(LocalVolumeId(app.id, "persistent-volume", "uuid"))))
    ))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a None")
    matchOpt shouldBe empty
  }

  test("Correct available volumes result in a match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer with matching persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId = LocalVolumeId(app.id, "persistent-volume", "uuid")
    val offer = f.offerWithVolumes(localVolumeId)
    val tasks = Seq(f.makeTask(app.id).copy(
      reservationWithVolumes = Some(ReservationWithVolumes(Seq(localVolumeId)))
    ))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a None")
    matchOpt should not be empty
    matchOpt.get.task.taskId shouldEqual tasks.head.taskId
    matchOpt.get.persistentVolumeResources should have size 1
    matchOpt.get.persistentVolumeResources.head shouldEqual offer.getResources(0)
  }

  test("Unwanted available volumes result in NO match") {
    val f = new Fixture

    Given("a resident app with persistent volumes and an offer with matching persistent volumes")
    val app = f.appWithPersistentVolume()
    val localVolumeId = LocalVolumeId(app.id, "persistent-volume", "uuid")
    val offer = f.offerWithVolumes(localVolumeId)
    val tasks = Seq(f.makeTask(app.id).copy(
      reservationWithVolumes = Some(ReservationWithVolumes(Seq(LocalVolumeId(app.id, "other-container", "uuid"))))
    ))

    When("We ask for a volume match")
    val matchOpt = PersistentVolumeMatcher.matchVolumes(offer, app, tasks)

    Then("We receive a None")
    matchOpt shouldBe empty
  }

  class Fixture {
    def makeTask(appId: PathId) = MarathonTestHelper.mininimalTask(appId)

    def offerWithVolumes(localVolumeIds: LocalVolumeId*) = {
      import scala.collection.JavaConverters._

      val diskResources = localVolumeIds.map { id =>
        Mesos.Resource.newBuilder()
          .setName("disk")
          .setType(Mesos.Value.Type.SCALAR)
          .setScalar(Mesos.Value.Scalar.newBuilder().setValue(10))
          .setRole("test")
          .setReservation(Mesos.Resource.ReservationInfo.newBuilder().setPrincipal("principal"))
          .setDisk(Mesos.Resource.DiskInfo.newBuilder()
            .setPersistence(Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(id.idString))
            .setVolume(Mesos.Volume.newBuilder()
              .setContainerPath(id.containerPath)
              .setMode(Mesos.Volume.Mode.RW)))
          .build()
      }
      MarathonTestHelper.makeBasicOffer()
        .clearResources()
        .addAllResources(diskResources.asJava)
        .build()
    }

    def appWithPersistentVolume(): AppDefinition = {
      MarathonTestHelper.makeBasicApp().copy(
        container = Some(mesosContainerWithPersistentVolume),
        residency = Some(Residency(
          Residency.defaultRelaunchEscalationTimeoutSeconds,
          Residency.defaultTaskLostBehaviour))
      )
    }

    lazy val mesosContainerWithPersistentVolume = Container(
      `type` = Mesos.ContainerInfo.Type.MESOS,
      volumes = Seq[Volume](
        PersistentVolume(
          containerPath = "persistent-volume",
          persistent = PersistentVolumeInfo(1024),
          mode = Mesos.Volume.Mode.RW
        )
      ),
      docker = None
    )

  }
}
