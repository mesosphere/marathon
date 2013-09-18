package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.Ranges
import mesosphere.mesos.TaskBuilder

/**
 * @author Tobi Knaup
 */

trait MarathonTestHelper {

  def makeBasicOffer(cpus: Double, mem: Double,
                     beginPort: Long, endPort: Long) = {
    val range = Value.Range.newBuilder
      .setBegin(beginPort)
      .setEnd(endPort)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range)
      .build
    val portsResource = Resource.newBuilder
      .setName("ports")
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build
    val cpusResource = TaskBuilder.scalarResource("cpus", cpus)
    val memResource = TaskBuilder.scalarResource("mem", mem)
    Offer.newBuilder
      .setId(OfferID.newBuilder.setValue("1"))
      .setFrameworkId(FrameworkID.newBuilder.setValue("marathon"))
      .setSlaveId(SlaveID.newBuilder.setValue("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(portsResource)
  }
}