package mesosphere.marathon
package integration.facades

object MesosFormats {
  import MesosFacade._
  import mesosphere.marathon.api.v2.json.Formats.FormatWithDefault
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit lazy val ITResourceScalarValueFormat: Format[ITResourceScalarValue] = Format(
    Reads.of[Double].map(ITResourceScalarValue(_)),
    Writes(scalarValue => JsNumber(scalarValue.value))
  )

  implicit lazy val ITResourcePortValueFormat: Format[ITResourcePortValue] = Format(
    Reads.of[String].map(ITResourcePortValue(_)),
    Writes(portValue => JsString(portValue.portString))
  )

  implicit lazy val ITResourceValueFormat: Format[ITResourceValue] = Format(
    Reads[ITResourceValue] {
      case JsNumber(value) => JsSuccess(ITResourceScalarValue(value.toDouble))
      case JsString(portsString) => JsSuccess(ITResourcePortValue(portsString))
      case _ => JsError("expected string or number")
    },
    Writes[ITResourceValue] {
      case ITResourceScalarValue(value) => JsNumber(value)
      case ITResourcePortValue(portsString) => JsString(portsString)
    }
  )

  implicit lazy val ITAttributesFormat: Format[ITAttributes] = Format(
    Reads.of[Map[String, ITResourceValue]].map(ITAttributes(_)),
    Writes[ITAttributes](a => Json.toJson(a.attributes))
  )

  implicit lazy val ITResourcesFormat: Format[ITResources] = Format(
    Reads.of[Map[String, ITResourceValue]].map(ITResources(_)),
    Writes[ITResources](resources => Json.toJson(resources.resources))
  )

  implicit lazy val ITAgentFormat: Format[ITAgent] = (
    (__ \ "id").format[String] ~
    (__ \ "attributes").formatNullable[ITAttributes].withDefault(ITAttributes.empty) ~
    (__ \ "resources").formatNullable[ITResources].withDefault(ITResources.empty) ~
    (__ \ "used_resources").formatNullable[ITResources].withDefault(ITResources.empty) ~
    (__ \ "offered_resources").formatNullable[ITResources].withDefault(ITResources.empty) ~
    (__ \ "reserved_resources").formatNullable[Map[String, ITResources]].withDefault(Map.empty) ~
    (__ \ "unreserved_resources").formatNullable[ITResources].withDefault(ITResources.empty)
  )(ITAgent.apply, unlift(ITAgent.unapply))

  implicit lazy val ITStatusFormat: Format[ITMesosState] = (
    (__ \ "version").format[String] ~
    (__ \ "git_sha").formatNullable[String] ~
    (__ \ "slaves").format[Seq[ITAgent]]
  )(ITMesosState.apply, unlift(ITMesosState.unapply))

  implicit lazy val ITFrameworkFormat: Format[ITFramework] = Json.format[ITFramework]

  implicit lazy val ITFrameworksFormat: Format[ITFrameworks] = Json.format[ITFrameworks]
}

