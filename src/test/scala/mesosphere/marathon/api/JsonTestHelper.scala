package mesosphere.marathon
package api

import diffson.jsonpatch.lcsdiff._
import diffson.jsonpatch.{Add, Copy, Operation}
import diffson.lcs.Patience
import diffson.playJson._
import mesosphere.marathon.raml.RamlSerializer
import org.scalatest.{Assertions, Matchers}
import play.api.libs.json._

import scala.collection.Map

object JsonTestHelper extends Assertions with Matchers {
  implicit val lcs = new Patience[JsValue]
  def assertSerializationRoundtripWorks[T](value: T, normalize: T => T = { t: T => t })(implicit format: Format[T]): Unit = {
    val normed = normalize(value)
    val json = Json.toJson(normed)
    val reread = Json.fromJson[T](json)
    withClue(s"for json:\n${Json.prettyPrint(json)}\n") {
      reread should be ('success)
      normed should be (normalize(reread.get))
    }
  }

  def assertSerializationRoundtripWithJacksonWorks[T](value: T, normalize: T => T = { t: T => t })(implicit format: Format[T]): Unit = {
    val normed = normalize(value)
    val json = RamlSerializer.serializer.writeValueAsString(normed)
    val jsonObj = Json.parse(json)
    val reread = Json.fromJson[T](jsonObj)
    withClue(s"for json:\n${Json.prettyPrint(jsonObj)}\n") {
      reread should be ('success)
      normed should be (normalize(reread.get))
    }
  }

  def assertSerializationIsSameForPlayAndJackson[T](value: T, normalize: T => T = { t: T => t })(implicit format: Format[T]): Unit = {
    val normed = normalize(value)
    val jsonJackson = RamlSerializer.serializer.writeValueAsString(normed)
    val jsonPlay = Json.toJson(normed).toString()

    jsonJackson should be (jsonPlay)
  }

  def assertThatJsonOf[T](value: T)(implicit writes: Writes[T]): AssertThatJsonString = {
    AssertThatJsonString(Json.prettyPrint(Json.toJson(value)))
  }

  def assertThatJacksonJsonOf[T](value: T): AssertThatJsonString = {
    val jsonJackson = RamlSerializer.serializer.writeValueAsString(value)
    AssertThatJsonString(jsonJackson)
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
    val actualJson = Json.parse(actual)
    private[this] def isAddition(op: Operation[JsValue]): Boolean = op match {
      case _: Add[_] | _: Copy[_] => true
      case _ => false
    }

    def containsEverythingInJsonString(expected: String): Unit = {
      val expectedJson = Json.parse(expected)
      val diff = diffson.diff(expectedJson, actualJson)
      require(diff.ops.forall(isAddition), s"unexpected differences in actual json:\n$actual\nexpected:\n$expected\n${diff.ops.filter(!isAddition(_))}")
    }

    def containsEverythingInJsonOf[T](expected: T)(implicit writes: Writes[T]): Unit = {
      correspondsToJsonString(Json.prettyPrint(Json.toJson(expected)))
    }

    def correspondsToJsonString(expected: String): Unit = {
      val expectedJson = Json.parse(expected)
      val diff = diffson.diff(expectedJson, actualJson)
      require(diff.ops.isEmpty, s"unexpected differences in actual json:\n$actual\nexpected:\n$expected\ndiff\n$diff")
    }

    def correspondsToJsonOf[T](expected: T)(implicit writes: Writes[T]): Unit = {
      correspondsToJsonString(Json.prettyPrint(Json.toJson(expected)))
    }
  }

}
