package mesosphere.marathon.api

import gnieh.diffson.{ Operation, JsonDiff, Add, Copy }
import org.scalatest.{ Matchers, Assertions }
import play.api.libs.json.{ JsArray, JsObject, JsNull, JsValue, Format, Json, Writes }

import scala.collection.Map

object JsonTestHelper extends Assertions with Matchers {
  def assertSerializationRoundtripWorks[T](value: T)(implicit format: Format[T]): Unit = {
    val json = Json.toJson(value)
    val reread = Json.fromJson(json)
    withClue(s"for json:\n${Json.prettyPrint(json)}\n") {
      reread should be ('success)
      value should be (reread.get)
    }
  }

  def assertThatJsonOf[T](value: T)(implicit writes: Writes[T]): AssertThatJsonString = {
    AssertThatJsonString(Json.prettyPrint(Json.toJson(value)))
  }

  def assertThatJsonString(actual: String): AssertThatJsonString = {
    AssertThatJsonString(actual)
  }

  def removeNullFieldValues(json: JsValue): JsValue = json match {
    case JsObject(fields) =>
      val withoutNullValues: Map[String, JsValue] = fields.filter {
        case (_, JsNull) => false
        case _           => true
      }
      val filterSubValues = withoutNullValues.mapValues {
        case v => removeNullFieldValues(v)
      }

      JsObject(filterSubValues)
    case JsArray(v) =>
      JsArray(v.map(removeNullFieldValues))
    case _: JsValue => json
  }

  case class AssertThatJsonString(actual: String) {
    private[this] def isAddition(op: Operation): Boolean = op match {
      case _: Add | _: Copy => true
      case _: Operation     => false
    }

    def containsEverythingInJsonString(expected: String): Unit = {
      val diff = JsonDiff.diff(expected, actual)
      require(diff.ops.forall(isAddition), s"unexpected differences in actual json:\n$actual\nexpected:\n$expected\n${diff.ops.filter(!isAddition(_))}")
    }

    def containsEverythingInJsonOf[T](expected: T)(implicit writes: Writes[T]): Unit = {
      correspondsToJsonString(Json.prettyPrint(Json.toJson(expected)))
    }

    def correspondsToJsonString(expected: String): Unit = {
      val diff = JsonDiff.diff(expected, actual)
      require(diff.ops.isEmpty, s"unexpected differences in actual json:\n$actual\nexpected:\n$expected\n$diff")
    }

    def correspondsToJsonOf[T](expected: T)(implicit writes: Writes[T]): Unit = {
      correspondsToJsonString(Json.prettyPrint(Json.toJson(expected)))
    }
  }

}
