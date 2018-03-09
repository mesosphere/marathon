package mesosphere.marathon
package core.launcher

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import org.apache.mesos.Protos.Resource.{ DiskInfo, ReservationInfo }
import org.apache.mesos.Protos.{ Offer, Resource, Value, Volume }

class InstanceOpTest extends UnitTest {
  "UnreserveAndDestroyVolumes" should {
    "unreserve resources even if there is no persistent volume offered" in {
      Given("reserved cpus")
      val cpusResource = Resource.newBuilder()
        .setName("cpus")
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder.setValue(0.1))
        .setRole("slave_public")
        .setReservation(ReservationInfo.newBuilder()
          .setPrincipal("dcos_marathon"))
        .build()

      And("unreserve and destroy volumes operation")
      val op = InstanceOp.UnreserveAndDestroyVolumes(
        stateOp = mock[InstanceUpdateOperation.MesosUpdate],
        resources = Seq(cpusResource))

      When("Mesos offer operations are built")
      val offerOps = op.offerOperations

      Then("there should be one operation to unreserve cpus")
      val unreserveOp = Offer.Operation.Unreserve.newBuilder()
        .addResources(cpusResource)
        .build()
      val expectedOp = Offer.Operation.newBuilder()
        .setType(Offer.Operation.Type.UNRESERVE)
        .setUnreserve(unreserveOp)
        .build()

      offerOps should have length 1
      offerOps should contain (expectedOp)
    }

    "unreserve resources and destroy persistent volumes" in {
      Given("reserved cpus")
      val cpusResource = Resource.newBuilder()
        .setName("cpus")
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder.setValue(0.1))
        .setRole("slave_public")
        .setReservation(ReservationInfo.newBuilder()
          .setPrincipal("dcos_marathon"))
        .build()

      And("a persistent volume")
      val pvResource = Resource.newBuilder()
        .setName("disk")
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder.setValue(50))
        .setRole("slave_public")
        .setReservation(ReservationInfo.newBuilder()
          .setPrincipal("dcos_marathon"))
        .setDisk(DiskInfo.newBuilder()
          .setPersistence(DiskInfo.Persistence.newBuilder()
            .setId("disk-id")
            .setPrincipal("dcos_marathon"))
          .setVolume(Volume.newBuilder()
            .setMode(Volume.Mode.RW)
            .setContainerPath("data")))
        .build()

      And("unreserve and destroy volumes operation")
      val op = InstanceOp.UnreserveAndDestroyVolumes(
        stateOp = mock[InstanceUpdateOperation.MesosUpdate],
        resources = Seq(cpusResource, pvResource))

      When("Mesos offer operations are built")
      val offerOps = op.offerOperations

      Then("there should be one operation to unreserve cpus")
      val pvResourceWithoutDisk = pvResource.toBuilder.clearDisk().build()
      val unreserveOp = Offer.Operation.newBuilder()
        .setType(Offer.Operation.Type.UNRESERVE)
        .setUnreserve(Offer.Operation.Unreserve.newBuilder()
          .addResources(cpusResource)
          .addResources(pvResourceWithoutDisk)
          .build())
        .build()

      val destroyOp = Offer.Operation.newBuilder()
        .setType(Offer.Operation.Type.DESTROY)
        .setDestroy(Offer.Operation.Destroy.newBuilder()
          .addVolumes(pvResource))
        .build()

      offerOps should have length 2
      offerOps should contain (destroyOp)
      offerOps should contain (unreserveOp)
    }
  }
}
