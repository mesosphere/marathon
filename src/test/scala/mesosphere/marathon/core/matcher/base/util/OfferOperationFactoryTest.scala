package mesosphere.marathon
package core.matcher.base.util

import mesosphere.UnitTest
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ DiskSource, PathId, PersistentVolume, PersistentVolumeInfo }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{ Protos => Mesos }

class OfferOperationFactoryTest extends UnitTest {

  "OfferOperationFactory" should {
    "Launch operation succeeds even if principal/role are not set" in {
      val f = new Fixture

      Given("a factory without principal or role")
      val factory = new OfferOperationFactory(None, None)
      val taskInfo = MarathonTestHelper.makeOneCPUTask(f.taskId).build()

      When("We create a launch operation")
      val operation = factory.launch(taskInfo)

      Then("the Offer Operation is created")
      operation.hasLaunch shouldEqual true
      operation.getLaunch.getTaskInfos(0) shouldEqual taskInfo
    }

    "Reserve operation fails when role is not set" in {
      val f = new Fixture

      Given("a factory without role")
      val factory = new OfferOperationFactory(Some("principal"), None)

      When("We create a reserve operation")
      val error = intercept[WrongConfigurationException] {
        factory.reserve(f.reservationLabels, Seq(Mesos.Resource.getDefaultInstance))
      }

      Then("A meaningful exception is thrown")
      error.getMessage should startWith("No role set")
    }

    "Reserve operation succeeds" in {
      val f = new Fixture

      Given("A simple task")
      val factory = new OfferOperationFactory(Some("principal"), Some("role"))
      val task = MarathonTestHelper.makeOneCPUTask(f.taskId)

      When("We create a reserve operation")
      val operation = factory.reserve(f.reservationLabels, task.getResourcesList.to[Seq])

      Then("The operation is as expected")
      operation.getType shouldEqual Mesos.Offer.Operation.Type.RESERVE
      operation.hasReserve shouldEqual true
      operation.getReserve.getResourcesCount shouldEqual task.getResourcesCount

      And("The resource is reserved")
      val resource = operation.getReserve.getResources(0)
      resource.getName shouldEqual "cpus"
      resource.getType shouldEqual Mesos.Value.Type.SCALAR
      resource.getScalar.getValue shouldEqual 1
      resource.getRole shouldEqual "role"
      resource.hasReservation shouldEqual true
      resource.getReservation.getPrincipal shouldEqual "principal"
    }

    "CreateVolumes operation succeeds" in {
      val f = new Fixture

      Given("a factory without principal")
      val factory = new OfferOperationFactory(Some("principal"), Some("role"))
      val task = MarathonTestHelper.makeOneCPUTask(f.taskId)
      val volumes = Seq(f.localVolume("mount"))
      val resource = MarathonTestHelper.scalarResource("disk", 1024)

      When("We create a reserve operation")
      val operation = factory.createVolumes(f.reservationLabels, volumes.map(v => (DiskSource.root, v)))

      Then("The operation is as expected")
      operation.getType shouldEqual Mesos.Offer.Operation.Type.CREATE
      operation.hasCreate shouldEqual true
      operation.getCreate.getVolumesCount shouldEqual volumes.size

      And("The volumes are correct")
      val volume = operation.getCreate.getVolumes(0)
      val originalVolume = volumes.head
      volume.getName shouldEqual "disk"
      volume.getRole shouldEqual "role"
      volume.getScalar.getValue shouldEqual 10
      volume.hasReservation shouldEqual true
      volume.getReservation.getPrincipal shouldEqual "principal"
      volume.hasDisk shouldEqual true
      volume.getDisk.hasPersistence shouldEqual true
      volume.getDisk.getPersistence.getId shouldEqual originalVolume.id.idString
      volume.getDisk.hasVolume shouldEqual true
      volume.getDisk.getVolume.getContainerPath shouldEqual originalVolume.persistentVolume.containerPath
      volume.getDisk.getVolume.getMode shouldEqual originalVolume.persistentVolume.mode
    }
  }
  class Fixture {
    val runSpecId = PathId("/my-app")
    val taskId = Task.Id.forRunSpec(runSpecId)
    val frameworkId = MarathonTestHelper.frameworkId
    val reservationLabels = TaskLabels.labelsForTask(frameworkId, taskId)
    val principal = Some("principal")
    val role = Some("role")
    val factory = new OfferOperationFactory(principal, role)

    def localVolume(containerPath: String): Task.LocalVolume = {
      val pv = PersistentVolume(
        containerPath = containerPath,
        persistent = PersistentVolumeInfo(size = 10),
        mode = Mesos.Volume.Mode.RW)
      Task.LocalVolume(Task.LocalVolumeId(runSpecId, pv), pv)
    }
  }
}
