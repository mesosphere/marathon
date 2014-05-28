package mesosphere.marathon

import org.junit.{Test}
import org.junit.Assert._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.{Type, Ranges, Scalar, Range}
import org.scalatest.junit.AssertionsForJUnit

class AvailableOfferTest extends AssertionsForJUnit {

  @Test
  def substractsAnAppDefinition() {
    val apdef1 = AppDefinitionNeeds(AppDefinition(cpus = 1, mem = 100, ports = Seq(8888)))
    val offer = Offer.newBuilder()
    offer.addResources(Resource.newBuilder().setName("cpus").setScalar(Scalar.newBuilder().setValue(3)).setType(Type.SCALAR))
    offer.addResources(Resource.newBuilder().setName("mem").setScalar(Scalar.newBuilder().setValue(2048)).setType(Type.SCALAR))
    val ranges = Ranges.newBuilder().addRange(Range.newBuilder().setBegin(8000).setEnd(8999))
    offer.addResources(Resource.newBuilder().setName("ports").setRanges(ranges).setType(Type.RANGES))
    offer.setId(OfferID.newBuilder().setValue("test9394"))
    offer.setFrameworkId(FrameworkID.newBuilder().setValue("framework9994"))
    offer.setSlaveId(SlaveID.newBuilder().setValue("slave948"))
    offer.setHostname("very.far.away")

    val avail: AvailableOffer = AvailableOffer(offer.build())
    val res = avail - apdef1
    assert(res.isDefined)
    val remain = res.get
    assert(remain.cpu == 2)
    assert(remain.mem == 1948)
    assert(remain.ports.contains((8000, 8887)))
    assert(remain.ports.contains((8889, 8999)))
  }

  @Test
  def returnsNoneForNoCpuLeft() {
    val apdef1 = AppDefinitionNeeds(AppDefinition(cpus = 8, mem = 100, ports = Seq(8888)))
    val offer = Offer.newBuilder()
    offer.addResources(Resource.newBuilder().setName("cpus").setScalar(Scalar.newBuilder().setValue(3)).setType(Type.SCALAR))
    offer.addResources(Resource.newBuilder().setName("mem").setScalar(Scalar.newBuilder().setValue(2048)).setType(Type.SCALAR))
    val ranges = Ranges.newBuilder().addRange(Range.newBuilder().setBegin(8000).setEnd(8999))
    offer.addResources(Resource.newBuilder().setName("ports").setRanges(ranges).setType(Type.RANGES))
    offer.setId(OfferID.newBuilder().setValue("test9394"))
    offer.setFrameworkId(FrameworkID.newBuilder().setValue("framework9994"))
    offer.setSlaveId(SlaveID.newBuilder().setValue("slave948"))
    offer.setHostname("very.far.away")

    val avail: AvailableOffer = AvailableOffer(offer.build())
    val res = avail - apdef1
    assert(res.isEmpty)
  }

  @Test
  def returnsNoneForNoMemLeft() {
    val apdef1 = AppDefinitionNeeds(AppDefinition(cpus = 1, mem = 4096, ports = Seq(8888)))
    val offer = Offer.newBuilder()
    offer.addResources(Resource.newBuilder().setName("cpus").setScalar(Scalar.newBuilder().setValue(3)).setType(Type.SCALAR))
    offer.addResources(Resource.newBuilder().setName("mem").setScalar(Scalar.newBuilder().setValue(2048)).setType(Type.SCALAR))
    val ranges = Ranges.newBuilder().addRange(Range.newBuilder().setBegin(8000).setEnd(8999))
    offer.addResources(Resource.newBuilder().setName("ports").setRanges(ranges).setType(Type.RANGES))
    offer.setId(OfferID.newBuilder().setValue("test9394"))
    offer.setFrameworkId(FrameworkID.newBuilder().setValue("framework9994"))
    offer.setSlaveId(SlaveID.newBuilder().setValue("slave948"))
    offer.setHostname("very.far.away")

    val avail: AvailableOffer = AvailableOffer(offer.build())
    val res = avail - apdef1
    assert(res.isEmpty)
  }

  @Test
  def returnsNoneForNoPortInRange() {
    val apdef1 = AppDefinitionNeeds(AppDefinition(cpus = 1, mem = 100, ports = Seq(9888)))
    val offer = Offer.newBuilder()
    offer.addResources(Resource.newBuilder().setName("cpus").setScalar(Scalar.newBuilder().setValue(3)).setType(Type.SCALAR))
    offer.addResources(Resource.newBuilder().setName("mem").setScalar(Scalar.newBuilder().setValue(2048)).setType(Type.SCALAR))
    val ranges = Ranges.newBuilder().addRange(Range.newBuilder().setBegin(8000).setEnd(8999))
    offer.addResources(Resource.newBuilder().setName("ports").setRanges(ranges).setType(Type.RANGES))
    offer.setId(OfferID.newBuilder().setValue("test9394"))
    offer.setFrameworkId(FrameworkID.newBuilder().setValue("framework9994"))
    offer.setSlaveId(SlaveID.newBuilder().setValue("slave948"))
    offer.setHostname("very.far.away")

    val avail: AvailableOffer = AvailableOffer(offer.build())
    val res = avail - apdef1
    assert(res.isEmpty)
  }
}