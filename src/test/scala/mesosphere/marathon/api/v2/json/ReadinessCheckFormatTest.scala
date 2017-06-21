package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.v2.AppNormalization
import mesosphere.marathon.core.readiness.{ ReadinessCheck, ReadinessCheckTestHelper }
import mesosphere.marathon.raml.Raml
import play.api.libs.json._

class ReadinessCheckFormatTest extends UnitTest {

  implicit val readinessCheckReads: Reads[ReadinessCheck] = Reads { js =>
    JsSuccess(Raml.fromRaml(AppNormalization.normalizeReadinessCheck(js.as[raml.ReadinessCheck])))
  }

  implicit val readinessCheckWrites: Writes[ReadinessCheck] = Writes { check =>
    raml.ReadinessCheck.playJsonFormat.writes(Raml.toRaml(check))
  }

  "ReadinessCheckFormat" should {
    "if we read empty JSON object, we use default values" in {
      Given("an empty Json Object")
      val obj = JsObject(Seq.empty)
      When("reading it to a readiness check")
      val check = obj.as[ReadinessCheck]
      Then("we get the default instance")
      check should equal(ReadinessCheckTestHelper.defaultHttp)
    }

    "defaultHttp readiness check is convertible from/to JSON" in {
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

    "alternativeHttps readiness check is convertible from/to JSON" in {
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
        |  "portName" : "dcos-migration-api",
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
}