package mesosphere.marathon.api.v2.json

import mesosphere.marathon.core.readiness.{ ReadinessCheckTestHelper, ReadinessCheck }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }
import play.api.libs.json.{ JsObject, Json }

class ReadinessCheckFormatTest extends FunSuite with Matchers with GivenWhenThen {
  import Formats._

  test("if we read empty JSON object, we use default values") {
    Given("an empty Json Object")
    val obj = JsObject(Seq.empty)
    When("reading it to a readiness check")
    val check = obj.as[ReadinessCheck]
    Then("we get the default instance")
    check should equal(ReadinessCheckTestHelper.defaultHttp)
  }

  test("defaultHttp readiness check is convertible from/to JSON") {
    Given("a defaultHttp readiness check")
    val defaultHttp = ReadinessCheckTestHelper.defaultHttp
    When("we are converting it to JSON")
    val json = Json.toJson(defaultHttp)
    val jsonString = Json.prettyPrint(json)
    Then("we get the expected JSON")
    val expectedJson =
      """
        |{
        |  "name" : "readinessCheck",
        |  "protocol" : "HTTP",
        |  "path" : "/",
        |  "portName" : "http-api",
        |  "intervalSeconds" : 30,
        |  "timeoutSeconds" : 10,
        |  "httpStatusCodesForReady" : [ 200 ],
        |  "preserveLastResponse" : false
        |}
      """.stripMargin.trim
    jsonString.trim should equal(expectedJson)

    When("We deserialize that JSON")
    val deserialized = Json.parse(expectedJson).as[ReadinessCheck]

    Then("We get back the original object")
    deserialized should equal(defaultHttp)
  }

  test("alternativeHttps readiness check is convertible from/to JSON") {
    Given("a alternativeHttps readiness check")
    val alternativeHttps = ReadinessCheckTestHelper.alternativeHttps
    When("we are converting it to JSON")
    val json = Json.toJson(alternativeHttps)
    val jsonString = Json.prettyPrint(json)
    Then("we get the expected JSON")
    val expectedJson =
      """
        |{
        |  "name" : "dcosMigrationApi",
        |  "protocol" : "HTTPS",
        |  "path" : "/v1/plan",
        |  "portName" : "dcosMigrationApi",
        |  "intervalSeconds" : 10,
        |  "timeoutSeconds" : 2,
        |  "httpStatusCodesForReady" : [ 201 ],
        |  "preserveLastResponse" : true
        |}
      """.stripMargin.trim
    jsonString.trim should equal(expectedJson)

    When("We deserialize that JSON")
    val deserialized = Json.parse(expectedJson).as[ReadinessCheck]

    Then("We get back the original object")
    deserialized should equal(alternativeHttps)
  }
}
