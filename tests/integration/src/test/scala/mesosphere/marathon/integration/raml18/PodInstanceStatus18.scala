package mesosphere.marathon
package integration.raml18

import mesosphere.marathon.raml._

// format: OFF
/**
  * ================================= NOTE =================================
  * This is a copy of [[mesosphere.marathon.raml.PodInstanceStatus]] class that doesn't the`role` field.
  * This is ONLY used in UpgradeIntegrationTest where we query old Marathon instances.
  * ========================================================================
  *
  * @param id Unique ID of this pod instance in the cluster.
  *   TODO(jdef) Probably represents the Mesos executor ID.
  * @param statusSince Time at which the status code was last modified.
  * @param message Human-friendly explanation for reason of the current status.
  * @param conditions Set of status conditions that apply to this pod instance.
  * @param agentHostname Hostname that this instance was launched on.
  *   May be an IP address if the agent was configured to advertise its hostname that way.
  * @param agentId The Mesos-generated ID of the agent upon which the instance was launched.
  * @param agentRegion The @region property of the agent.
  * @param agentZone The @zone property of the agent.
  * @param resources Sum of all resources allocated for this pod instance.
  *   May include additional, system-allocated resources for the default executor.
  * @param networks Status of the networks to which this instance is attached.
  * @param containers status for each running container of this instance.
  * @param specReference Location of the version of the pod specification this instance was created from.
  *   maxLength: 1024
  *   minLength: 1
  * @param lastUpdated Time that this status was last checked and updated (even if nothing changed)
  * @param lastChanged Time that this status was last modified (some aspect of status did change)
  */
case class PodInstanceStatus18(id: String, status: PodInstanceState, statusSince: java.time.OffsetDateTime, message: Option[String] = None, conditions: scala.collection.immutable.Seq[StatusCondition] = Nil, agentHostname: Option[String] = None, agentId: Option[String] = None, agentRegion: Option[String] = None, agentZone: Option[String] = None, resources: Option[Resources] = None, networks: scala.collection.immutable.Seq[NetworkStatus] = Nil, containers: scala.collection.immutable.Seq[ContainerStatus] = Nil, specReference: Option[String] = None, localVolumes: scala.collection.immutable.Seq[LocalVolumeId] = Nil, lastUpdated: java.time.OffsetDateTime, lastChanged: java.time.OffsetDateTime) extends RamlGenerated

object PodInstanceStatus18 {
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._
  implicit object playJsonFormat extends play.api.libs.json.Format[PodInstanceStatus18] {
    def reads(json: play.api.libs.json.JsValue): play.api.libs.json.JsResult[PodInstanceStatus18] = {
      val id = json.\("id").validate[String](play.api.libs.json.JsPath.read[String])
      val status = json.\("status").validate[PodInstanceState](play.api.libs.json.JsPath.read[PodInstanceState])
      val statusSince = json.\("statusSince").validate[java.time.OffsetDateTime](play.api.libs.json.JsPath.read[java.time.OffsetDateTime])
      val message = json.\("message").validateOpt[String](play.api.libs.json.JsPath.read[String])
      val conditions = json.\("conditions").validateOpt[scala.collection.immutable.Seq[StatusCondition]](play.api.libs.json.JsPath.read[scala.collection.immutable.Seq[StatusCondition]]).map(_.getOrElse(scala.collection.immutable.Seq[StatusCondition]()))
      val agentHostname = json.\("agentHostname").validateOpt[String](play.api.libs.json.JsPath.read[String])
      val agentId = json.\("agentId").validateOpt[String](play.api.libs.json.JsPath.read[String])
      val agentRegion = json.\("agentRegion").validateOpt[String](play.api.libs.json.JsPath.read[String])
      val agentZone = json.\("agentZone").validateOpt[String](play.api.libs.json.JsPath.read[String])
      val resources = json.\("resources").validateOpt[Resources](play.api.libs.json.JsPath.read[Resources])
      val networks = json.\("networks").validateOpt[scala.collection.immutable.Seq[NetworkStatus]](play.api.libs.json.JsPath.read[scala.collection.immutable.Seq[NetworkStatus]]).map(_.getOrElse(scala.collection.immutable.Seq[NetworkStatus]()))
      val containers = json.\("containers").validateOpt[scala.collection.immutable.Seq[ContainerStatus]](play.api.libs.json.JsPath.read[scala.collection.immutable.Seq[ContainerStatus]]).map(_.getOrElse(scala.collection.immutable.Seq[ContainerStatus]()))
      val specReference = json.\("specReference").validateOpt[String](play.api.libs.json.JsPath.read[String](maxLength[String](1024) keepAnd minLength[String](1)))
      val localVolumes = json.\("localVolumes").validateOpt[scala.collection.immutable.Seq[LocalVolumeId]](play.api.libs.json.JsPath.read[scala.collection.immutable.Seq[LocalVolumeId]]).map(_.getOrElse(scala.collection.immutable.Seq[LocalVolumeId]()))
      val lastUpdated = json.\("lastUpdated").validate[java.time.OffsetDateTime](play.api.libs.json.JsPath.read[java.time.OffsetDateTime])
      val lastChanged = json.\("lastChanged").validate[java.time.OffsetDateTime](play.api.libs.json.JsPath.read[java.time.OffsetDateTime])
      val _errors = Seq(("id", id), ("status", status), ("statusSince", statusSince), ("message", message), ("conditions", conditions), ("agentHostname", agentHostname), ("agentId", agentId), ("agentRegion", agentRegion), ("agentZone", agentZone), ("resources", resources), ("networks", networks), ("containers", containers), ("specReference", specReference), ("localVolumes", localVolumes), ("lastUpdated", lastUpdated), ("lastChanged", lastChanged)).collect({
        case (field, e:play.api.libs.json.JsError) => e.repath(play.api.libs.json.JsPath.\(field)).asInstanceOf[play.api.libs.json.JsError]
      })
      if (_errors.nonEmpty) _errors.reduceOption[play.api.libs.json.JsError](_.++(_)).getOrElse(_errors.head)
      else play.api.libs.json.JsSuccess(PodInstanceStatus18(id = id.get, status = status.get, statusSince = statusSince.get, message = message.get, conditions = conditions.get, agentHostname = agentHostname.get, agentId = agentId.get, agentRegion = agentRegion.get, agentZone = agentZone.get, resources = resources.get, networks = networks.get, containers = containers.get, specReference = specReference.get, localVolumes = localVolumes.get, lastUpdated = lastUpdated.get, lastChanged = lastChanged.get))
    }
    def writes(o: PodInstanceStatus18): play.api.libs.json.JsValue = {
      val id = play.api.libs.json.Json.toJson(o.id)
      val status = play.api.libs.json.Json.toJson(o.status)
      val statusSince = play.api.libs.json.Json.toJson(o.statusSince)
      val message = play.api.libs.json.Json.toJson(o.message)
      val conditions = play.api.libs.json.Json.toJson(o.conditions)
      val agentHostname = play.api.libs.json.Json.toJson(o.agentHostname)
      val agentId = play.api.libs.json.Json.toJson(o.agentId)
      val agentRegion = play.api.libs.json.Json.toJson(o.agentRegion)
      val agentZone = play.api.libs.json.Json.toJson(o.agentZone)
      val resources = play.api.libs.json.Json.toJson(o.resources)
      val networks = play.api.libs.json.Json.toJson(o.networks)
      val containers = play.api.libs.json.Json.toJson(o.containers)
      val specReference = play.api.libs.json.Json.toJson(o.specReference)
      val localVolumes = play.api.libs.json.Json.toJson(o.localVolumes)
      val lastUpdated = play.api.libs.json.Json.toJson(o.lastUpdated)
      val lastChanged = play.api.libs.json.Json.toJson(o.lastChanged)
      play.api.libs.json.JsObject(Seq(("id", id), ("status", status), ("statusSince", statusSince), ("message", message), ("conditions", conditions), ("agentHostname", agentHostname), ("agentId", agentId), ("agentRegion", agentRegion), ("agentZone", agentZone), ("resources", resources), ("networks", networks), ("containers", containers), ("specReference", specReference), ("localVolumes", localVolumes), ("lastUpdated", lastUpdated), ("lastChanged", lastChanged)).filter(_._2 != play.api.libs.json.JsNull).++(Seq.empty))
    }
  }
  val DefaultMessage: Option[String] = None
  val DefaultConditions: scala.collection.immutable.Seq[StatusCondition] = Nil
  val DefaultAgentHostname: Option[String] = None
  val DefaultAgentId: Option[String] = None
  val DefaultAgentRegion: Option[String] = None
  val DefaultAgentZone: Option[String] = None
  val DefaultResources: Option[Resources] = None
  val DefaultNetworks: scala.collection.immutable.Seq[NetworkStatus] = Nil
  val DefaultContainers: scala.collection.immutable.Seq[ContainerStatus] = Nil
  val DefaultSpecReference: Option[String] = None
  val DefaultLocalVolumes: scala.collection.immutable.Seq[LocalVolumeId] = Nil
  val ConstraintSpecreferenceMaxlength = 1024
  val ConstraintSpecreferenceMinlength = 1
}
