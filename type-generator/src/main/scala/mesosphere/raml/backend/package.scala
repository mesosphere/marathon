package mesosphere.raml

import treehugger.forest._
import definitions._
import treehuggerDSL._

package object backend {

  val AdditionalProperties = "additionalProperties"
  val baseTypeTable: Map[String, Symbol] =
    Map(
      "string" -> StringClass,
      "int8" -> ByteClass,
      "int16" -> ShortClass,
      "int32" -> IntClass,
      "integer" -> IntClass,
      "int64" -> LongClass,
      "long" -> LongClass,
      "float" -> FloatClass,
      "double" -> DoubleClass,
      "boolean" -> BooleanClass,
      "date-only" -> RootClass.newClass("java.time.LocalDate"),
      "time-only" -> RootClass.newClass("java.time.LocalTime"),
      "datetime-only" -> RootClass.newClass("java.time.LocalDateTime"),
      "datetime" -> RootClass.newClass("java.time.OffsetDateTime"),
      "RamlGenerated" -> RootClass.newClass("RamlGenerated"),
      "RamlConstraints" -> RootClass.newClass("RamlConstraints")
    )

  val builtInTypes = Set(
    "Byte",
    "Short",
    "Int",
    "Long",
    "Float",
    "Double",
    "Boolean",
    "String",
    "java.time.LocalDate",
    "java.time.LocalTime",
    "java.time.LocalDateTime",
    "java.time.OffsetDateTime"
  )

  val TryClass = RootClass.newClass("scala.util.Try")

  val SeqClass = RootClass.newClass("scala.collection.immutable.Seq")
  val SetClass = RootClass.newClass("Set")
  val IterableClass = RootClass.newClass("Iterable")

  def TYPE_SEQ(typ: Type): Type = SeqClass TYPE_OF typ

  val PlayJsonFormat = RootClass.newClass("play.api.libs.json.Format")

  def PLAY_JSON_FORMAT(typ: Type): Type = PlayJsonFormat TYPE_OF typ

  val PlayJsonResult = RootClass.newClass("play.api.libs.json.JsResult")

  def PLAY_JSON_RESULT(typ: Type): Type = PlayJsonResult TYPE_OF typ

  val PlayJson = RootClass.newClass("play.api.libs.json.Json")
  val PlayJsValue = RootClass.newClass("play.api.libs.json.JsValue")
  val PlayJsString = RootClass.newClass("play.api.libs.json.JsString")
  val PlayJsNumber = RootClass.newClass("play.api.libs.json.JsNumber")
  val PlayJsObject = RootClass.newClass("play.api.libs.json.JsObject")
  val PlayJsArray = RootClass.newClass("play.api.libs.json.JsArray")
  val PlayValidationError = RootClass.newClass("play.api.libs.json.JsonValidationError")
  val PlayJsError = RootClass.newClass("play.api.libs.json.JsError")
  val PlayJsSuccess = RootClass.newClass("play.api.libs.json.JsSuccess")
  val PlayReads = RootClass.newClass("play.api.libs.json.Reads")
  def PLAY_JSON_READS(typ: Type): Type = PlayReads TYPE_OF typ
  val PlayWrites = RootClass.newClass("play.api.libs.json.Writes")
  def PLAY_JSON_WRITES(typ: Type): Type = PlayWrites TYPE_OF typ
  val PlayPath = RootClass.newClass("play.api.libs.json.JsPath")

  val PlayJsNull = REF("play.api.libs.json.JsNull")

  val NoScalaFormat = "format: OFF"

  def camelify(name: String): String = name.toLowerCase.capitalize

  def underscoreToCamel(name: String) =
    "(/|_|\\,)([a-z\\d])".r.replaceAllIn(
      name,
      { m =>
        m.group(2).toUpperCase()
      }
    )

  def scalaFieldName(name: String): String = {
    if (name.contains("-")) s"`$name`"
    else name
  }
}
