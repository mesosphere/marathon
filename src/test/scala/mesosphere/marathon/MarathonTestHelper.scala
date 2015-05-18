package mesosphere.marathon

import mesosphere.marathon.tasks.IterativeOfferMatcher
import org.apache.mesos.Protos.Offer
import org.rogach.scallop.ScallopConf
import mesosphere.marathon.state.AppDefinition
import mesosphere.mesos.protos._
import mesosphere.marathon.state.PathId._

trait MarathonTestHelper {

  import mesosphere.mesos.protos.Implicits._

  def makeConfig(args: String*): MarathonConf = {
    val opts = new ScallopConf(args) with MarathonConf {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
    }
    opts.afterInit()
    opts
  }

  def defaultConfig(
    maxTasksPerOffer: Int = 1,
    maxTasksPerOfferCycle: Int = 10,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None): MarathonConf = {

    var args = Seq(
      "--master", "127.0.0.1:5050",
      "--max_tasks_per_offer", maxTasksPerOffer.toString,
      "--max_tasks_per_offer_cycle", maxTasksPerOfferCycle.toString
    )

    mesosRole.foreach(args ++= Seq("--mesos_role", _))
    acceptedResourceRoles.foreach(v => args ++= Seq("--default_accepted_resource_roles", v.mkString(",")))
    makeConfig(args: _*)
  }

  def makeBasicOffer(cpus: Double = 4.0, mem: Double = 16000, disk: Double = 1.0,
                     beginPort: Int = 31000, endPort: Int = 32000, role: String = "*"): Offer.Builder = {
    val cpusResource = ScalarResource(Resource.CPUS, cpus, role = role)
    val memResource = ScalarResource(Resource.MEM, mem, role = role)
    val diskResource = ScalarResource(Resource.DISK, disk, role = role)
    val portsResource = if (beginPort <= endPort) {
      Some(RangesResource(
        Resource.PORTS,
        Seq(Range(beginPort.toLong, endPort.toLong)),
        role
      ))
    }
    else {
      None
    }
    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)

    portsResource.foreach(offerBuilder.addResources(_))

    offerBuilder
  }

  def makeBasicOfferWithRole(cpus: Double, mem: Double, disk: Double,
                             beginPort: Int, endPort: Int, role: String) = {
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(Range(beginPort.toLong, endPort.toLong)),
      role
    )
    val cpusResource = ScalarResource(Resource.CPUS, cpus, role)
    val memResource = ScalarResource(Resource.MEM, mem, role)
    val diskResource = ScalarResource(Resource.DISK, disk, role)
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
    id = "test-app".toPath,
    cpus = 1,
    mem = 64,
    disk = 1,
    executor = "//cmd"
  )
}

object MarathonTestHelper extends MarathonTestHelper
