package mesosphere.marathon
package core.matcher.base.util

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.{Instance, LocalVolume, LocalVolumeId, Reservation}
import mesosphere.marathon.core.launcher.InstanceOpFactory
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.ResourceProviderID
import org.apache.mesos.{Protos => Mesos}

class OfferOperationFactoryTest extends UnitTest {

  "OfferOperationFactory" should {
    "Launch operation succeeds even if principal/role are not set" in {
      val f = new Fixture

      Given("a factory without principal or role")
      val factory = new OfferOperationFactory(f.metrics, None, None)
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
      val factory = new OfferOperationFactory(f.metrics, Some("principal"), None)

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
      val factory = new OfferOperationFactory(f.metrics, Some("principal"), Some("role"))
      val task = MarathonTestHelper.makeOneCPUTask(f.taskId)

      When("We create a reserve operation")
      val operations = factory.reserve(f.reservationLabels, task.getResourcesList.to[Seq])

      Then("The operation is as expected")
      operations.length shouldEqual 1
      val operation = operations.head
      operation.getType shouldEqual Mesos.Offer.Operation.Type.RESERVE
      operation.hasReserve shouldEqual true
      operation.getReserve.getResourcesCount shouldEqual task.getResourcesCount

      And("The resource is reserved")
      val resource = operation.getReserve.getResources(0)
      resource.getName shouldEqual "cpus"
      resource.getType shouldEqual Mesos.Value.Type.SCALAR
      resource.getScalar.getValue shouldEqual 1
      resource.getRole shouldEqual "role": @silent
      resource.hasReservation shouldEqual true
      resource.getReservation.getPrincipal shouldEqual "principal"
    }

    "CreateVolumes operation succeeds" in {
      val f = new Fixture

      Given("a factory without principal")
      val factory = new OfferOperationFactory(f.metrics, Some("principal"), Some("role"))
      val volume1 = f.localVolume("mount1")
      val volume2 = f.localVolume("mount2")

      When("We create a reserve operation")
      val offeredVolume1 = InstanceOpFactory.OfferedVolume(None, DiskSource.root, volume1)
      val offeredVolume2 =
        InstanceOpFactory.OfferedVolume(Some(ResourceProviderID("pID")), DiskSource.root, volume2)
      val offeredVolumes = Seq(offeredVolume1, offeredVolume2)
      val operations = factory.createVolumes(f.reservationLabels, offeredVolumes)

      Then("The operation is as expected")
      operations.length shouldEqual 2

      val (operationWithProviderId, operationWithoutProviderId) =
        if (operations.head.getCreate.getVolumesList.exists(_.hasProviderId)) {
          (operations.head, operations.last)
        } else {
          (operations.last, operations.head)
        }

      operationWithProviderId.getType shouldEqual Mesos.Offer.Operation.Type.CREATE
      operationWithProviderId.hasCreate shouldEqual true
      operationWithProviderId.getCreate.getVolumesCount shouldEqual 1
      operationWithProviderId.getCreate.getVolumesList.exists(_.hasProviderId) shouldEqual true

      operationWithoutProviderId.getType shouldEqual Mesos.Offer.Operation.Type.CREATE
      operationWithoutProviderId.hasCreate shouldEqual true
      operationWithoutProviderId.getCreate.getVolumesCount shouldEqual 1
      operationWithoutProviderId.getCreate.getVolumesList.exists(_.hasProviderId) shouldEqual false

      And("The volumes are correct")
      val volumeWithProviderId = operationWithProviderId.getCreate.getVolumes(0)
      volumeWithProviderId.getName shouldEqual "disk"
      volumeWithProviderId.getRole shouldEqual "role": @silent
      volumeWithProviderId.getScalar.getValue shouldEqual 10
      volumeWithProviderId.hasReservation shouldEqual true
      volumeWithProviderId.getReservation.getPrincipal shouldEqual "principal"
      volumeWithProviderId.hasDisk shouldEqual true
      volumeWithProviderId.getDisk.hasPersistence shouldEqual true
      volumeWithProviderId.getDisk.getPersistence.getId shouldEqual volume2.id.idString
      volumeWithProviderId.getDisk.hasVolume shouldEqual true
      volumeWithProviderId.getDisk.getVolume.getContainerPath shouldEqual volume2.mount.mountPath
      volumeWithProviderId.getDisk.getVolume.getMode shouldEqual Mesos.Volume.Mode.RW

      val volumeWithoutProviderId = operationWithoutProviderId.getCreate.getVolumes(0)
      volumeWithoutProviderId.getName shouldEqual "disk"
      volumeWithoutProviderId.getRole shouldEqual "role": @silent
      volumeWithoutProviderId.getScalar.getValue shouldEqual 10
      volumeWithoutProviderId.hasReservation shouldEqual true
      volumeWithoutProviderId.getReservation.getPrincipal shouldEqual "principal"
      volumeWithoutProviderId.hasDisk shouldEqual true
      volumeWithoutProviderId.getDisk.hasPersistence shouldEqual true
      volumeWithoutProviderId.getDisk.getPersistence.getId shouldEqual volume1.id.idString
      volumeWithoutProviderId.getDisk.hasVolume shouldEqual true
      volumeWithoutProviderId.getDisk.getVolume.getContainerPath shouldEqual volume1.mount.mountPath
      volumeWithoutProviderId.getDisk.getVolume.getMode shouldEqual Mesos.Volume.Mode.RW
    }
  }
  class Fixture {
    val runSpecId = PathId("/my-app")
    val instanceId = Instance.Id.forRunSpec(runSpecId)
    val taskId = Task.Id(instanceId)
    val reservationId = Reservation.Id(instanceId)
    val frameworkId = MarathonTestHelper.frameworkId
    val reservationLabels = TaskLabels.labelsForTask(frameworkId, reservationId)
    val principal = Some("principal")
    val role = Some("role")
    val metrics = DummyMetrics
    val factory = new OfferOperationFactory(metrics, principal, role)

    def localVolume(mountPath: String): LocalVolume = {
      val pv = PersistentVolume(None, PersistentVolumeInfo(size = 10))
      val mount = VolumeMount(None, mountPath)
      LocalVolume(LocalVolumeId(runSpecId, pv, mount), pv, mount)
    }
  }
}
