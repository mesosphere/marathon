package mesosphere.marathon
package integration.raml18

import mesosphere.marathon.raml.{Pod, PodState, RamlGenerated, TerminationHistory}

// format: OFF
/**
  * ================================= NOTE =================================
  * This is a copy of [[mesosphere.marathon.raml.PodStatus]] class which uses [[PodInstanceStatus18]] class that doesn't
  * have `role` field. This is ONLY used in UpgradeIntegrationTest where we query old Marathon instances.
  * ========================================================================
  *
  * Pod status communicates the lifecycle phase of the pod, current instance and container
  * status, and recent termination status history.
  * @param id minLength: 1
  *   pattern: <pre>^(\/?((\.\.)|(([a-z0-9]|[a-z0-9][a-z0-9\-]*[a-z0-9])\.)*([a-z0-9]|[a-z0-9][a-z0-9\-]*[a-z0-9]))?($|\/))+$</pre>
  * @param spec The latest version of the pod specification (that has the same pod ID).
  * @param statusSince Time at which the status code was last modified.
  * @param message Human-friendly explanation for reason of the current status.
  * @param terminationHistory List of most recent instance terminations.
  *   TODO(jdef) determine how many items might show up here; current thinking is .. not many
  * @param lastUpdated Time that this status object was last checked and updated (even if nothing changed)
  * @param lastChanged Time that this status object was last modified (some aspect of status did change)
  */
case class PodStatus18(id: String, spec: Pod, status: PodState, statusSince: java.time.OffsetDateTime, message: Option[String] = None, instances: scala.collection.immutable.Seq[PodInstanceStatus18] = Nil, terminationHistory: scala.collection.immutable.Seq[TerminationHistory] = Nil, lastUpdated: java.time.OffsetDateTime, lastChanged: java.time.OffsetDateTime) extends RamlGenerated

object PodStatus18 {
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._
  implicit object playJsonFormat extends play.api.libs.json.Format[PodStatus18] {
    def reads(json: play.api.libs.json.JsValue): play.api.libs.json.JsResult[PodStatus18] = {
      val id = json.\("id").validate[String](play.api.libs.json.JsPath.read[String](minLength[String](1) keepAnd pattern("^(\\/?((\\.\\.)|(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9]))?($|\\/))+$".r)))
      val spec = json.\("spec").validate[Pod](play.api.libs.json.JsPath.read[Pod])
      val status = json.\("status").validate[PodState](play.api.libs.json.JsPath.read[PodState])
      val statusSince = json.\("statusSince").validate[java.time.OffsetDateTime](play.api.libs.json.JsPath.read[java.time.OffsetDateTime])
      val message = json.\("message").validateOpt[String](play.api.libs.json.JsPath.read[String])
      val instances = json.\("instances").validateOpt[scala.collection.immutable.Seq[PodInstanceStatus18]](play.api.libs.json.JsPath.read[scala.collection.immutable.Seq[PodInstanceStatus18]]).map(_.getOrElse(scala.collection.immutable.Seq[PodInstanceStatus18]()))
      val terminationHistory = json.\("terminationHistory").validateOpt[scala.collection.immutable.Seq[TerminationHistory]](play.api.libs.json.JsPath.read[scala.collection.immutable.Seq[TerminationHistory]]).map(_.getOrElse(scala.collection.immutable.Seq[TerminationHistory]()))
      val lastUpdated = json.\("lastUpdated").validate[java.time.OffsetDateTime](play.api.libs.json.JsPath.read[java.time.OffsetDateTime])
      val lastChanged = json.\("lastChanged").validate[java.time.OffsetDateTime](play.api.libs.json.JsPath.read[java.time.OffsetDateTime])
      val _errors = Seq(("id", id), ("spec", spec), ("status", status), ("statusSince", statusSince), ("message", message), ("instances", instances), ("terminationHistory", terminationHistory), ("lastUpdated", lastUpdated), ("lastChanged", lastChanged)).collect({
        case (field, e:play.api.libs.json.JsError) => e.repath(play.api.libs.json.JsPath.\(field)).asInstanceOf[play.api.libs.json.JsError]
      })
      if (_errors.nonEmpty) _errors.reduceOption[play.api.libs.json.JsError](_.++(_)).getOrElse(_errors.head)
      else play.api.libs.json.JsSuccess(PodStatus18(id = id.get, spec = spec.get, status = status.get, statusSince = statusSince.get, message = message.get, instances = instances.get, terminationHistory = terminationHistory.get, lastUpdated = lastUpdated.get, lastChanged = lastChanged.get))
    }
    def writes(o: PodStatus18): play.api.libs.json.JsValue = {
      val id = play.api.libs.json.Json.toJson(o.id)
      val spec = play.api.libs.json.Json.toJson(o.spec)
      val status = play.api.libs.json.Json.toJson(o.status)
      val statusSince = play.api.libs.json.Json.toJson(o.statusSince)
      val message = play.api.libs.json.Json.toJson(o.message)
      val instances = play.api.libs.json.Json.toJson(o.instances)
      val terminationHistory = play.api.libs.json.Json.toJson(o.terminationHistory)
      val lastUpdated = play.api.libs.json.Json.toJson(o.lastUpdated)
      val lastChanged = play.api.libs.json.Json.toJson(o.lastChanged)
      play.api.libs.json.JsObject(Seq(("id", id), ("spec", spec), ("status", status), ("statusSince", statusSince), ("message", message), ("instances", instances), ("terminationHistory", terminationHistory), ("lastUpdated", lastUpdated), ("lastChanged", lastChanged)).filter(_._2 != play.api.libs.json.JsNull).++(Seq.empty))
    }
  }
  val DefaultMessage: Option[String] = None
  val DefaultInstances: scala.collection.immutable.Seq[PodInstanceStatus18] = Nil
  val DefaultTerminationHistory: scala.collection.immutable.Seq[TerminationHistory] = Nil
  val ConstraintIdMinlength = 1
  val ConstraintIdPattern = "^(\\/?((\\.\\.)|(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9]))?($|\\/))+$".r
}
