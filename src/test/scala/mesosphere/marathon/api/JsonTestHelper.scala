package mesosphere.marathon
package api

import gnieh.diffson.playJson._
import org.scalatest.{ Assertions, Matchers }
import play.api.libs.json.{ Format, JsArray, JsNull, JsObject, JsValue, Json, Writes }

import scala.collection.Map

object JsonTestHelper extends Assertions with Matchers {
  def assertSerializationRoundtripWorks[T](value: T, normalize: T => T = { t: T => t })(implicit format: Format[T]): Unit = {
    val normed = normalize(value)
    val json = Json.toJson(normed)
    val reread = Json.fromJson[T](json)
    withClue(s"for json:\n${Json.prettyPrint(json)}\n") {
      reread should be ('success)
      normed should be (normalize(reread.get))
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
        case _ => true
      }
      val filterSubValues = withoutNullValues.map { case (k, v) => k -> removeNullFieldValues(v) }

      JsObject(filterSubValues)
    case JsArray(v) =>
      JsArray(v.map(removeNullFieldValues))
    case _: JsValue => json
  }

  case class AssertThatJsonString(actual: String) {
    private[this] def isAddition(op: Operation): Boolean = op match {
      case _: Add | _: Copy => true
      case _: Operation => false
    }

    def containsEverythingInJsonString(expected: String): Unit = {
      val diff = JsonDiff.diff(expected, actual, remember = false)
      require(diff.ops.forall(isAddition), s"unexpected differences in actual json:\n$actual\nexpected:\n$expected\n${diff.ops.filter(!isAddition(_))}")
    }

    def containsEverythingInJsonOf[T](expected: T)(implicit writes: Writes[T]): Unit = {
      correspondsToJsonString(Json.prettyPrint(Json.toJson(expected)))
    }

    def correspondsToJsonString(expected: String): Unit = {
      val diff = JsonDiff.diff(expected, actual, remember = false)
      require(diff.ops.isEmpty, s"unexpected differences in actual json:\n$actual\nexpected:\n$expected\ndiff\n$diff")
    }

    def correspondsToJsonOf[T](expected: T)(implicit writes: Writes[T]): Unit = {
      correspondsToJsonString(Json.prettyPrint(Json.toJson(expected)))
    }
  }

}
