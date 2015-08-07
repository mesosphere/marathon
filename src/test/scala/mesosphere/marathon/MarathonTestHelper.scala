package mesosphere.marathon

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.main.JsonSchemaFactory
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.Offer
import org.rogach.scallop.ScallopConf

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
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None,
    reviveOffersForNewApps: Option[Boolean] = Some(true),
    declineOfferDuration: Option[Long] = Some(3600000)): MarathonConf = {

    var args = Seq(
      "--master", "127.0.0.1:5050",
      "--max_tasks_per_offer", maxTasksPerOffer.toString,
      "--max_tasks_per_offer_cycle", maxTasksPerOfferCycle.toString
    )

    reviveOffersForNewApps.foreach(_ => args ++= Seq("--revive_offers_for_new_apps"))
    declineOfferDuration.foreach(duration => args ++= Seq("--decline_offer_duration", duration.toString))

    mesosRole.foreach(args ++= Seq("--mesos_role", _))
    acceptedResourceRoles.foreach(v => args ++= Seq("--default_accepted_resource_roles", v.mkString(",")))
    envVarsPrefix.foreach(args ++ Seq("--env_vars_prefix", _))
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
    cpus = 1.0,
    mem = 64.0,
    disk = 1.0,
    executor = "//cmd"
  )

  def getSchemaMapper() = {
    import com.fasterxml.jackson.annotation.JsonInclude
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val schemaMapper = new ObjectMapper
    schemaMapper.registerModule(DefaultScalaModule)
    schemaMapper.registerModule(new MarathonModule)
    schemaMapper.registerModule(CaseClassModule)
    schemaMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
    schemaMapper
  }
  val schemaMapper = getSchemaMapper()

  def getAppSchema() = {
    val appJson = "/mesosphere/marathon/api/v2/AppDefinition.json"
    val appDefinition = JsonLoader.fromResource(appJson)
    val factory = JsonSchemaFactory.byDefault()
    factory.getJsonSchema(appDefinition)
  }
  val appSchema = getAppSchema()

  def validateJsonSchema(app: V2AppDefinition, valid: Boolean = true) {
    val appStr = schemaMapper.writeValueAsString(app)
    val appJson = JsonLoader.fromString(appStr)
    assert(appSchema.validate(appJson).isSuccess == valid)
  }
}

object MarathonTestHelper extends MarathonTestHelper
