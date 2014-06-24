package mesosphere.marathon

import org.apache.mesos.Protos.Offer
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.mesos.protos._

/**
  * @author Tobi Knaup
  */

trait MarathonTestHelper {

  import mesosphere.mesos.protos.Implicits._

  def makeBasicOffer(cpus: Double = 4.0, mem: Double = 16000, disk: Double = 1.0,
                     beginPort: Int = 31000, endPort: Int = 32000) = {
    val cpusResource = ScalarResource(Resource.CPUS, cpus)
    val memResource = ScalarResource(Resource.MEM, mem)
    val diskResource = ScalarResource(Resource.DISK, disk)
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(Range(beginPort, endPort)),
      "*"
    )
    Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)
  }

  def makeBasicOfferWithRole(cpus: Double, mem: Double, disk: Double,
                             beginPort: Int, endPort: Int, role: String) = {
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(Range(beginPort, endPort)),
      role
    )
    val cpusResource = ScalarResource("cpus", cpus, role)
    val memResource = ScalarResource("mem", mem, role)
    val diskResource = ScalarResource("disk", disk, role)
    Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)
  }

  def makeBasicApp() = AppDefinition(
    id = "testApp",
    cpus = 1,
    mem = 64,
    disk = 1,
    executor = "//cmd"
  )
}
