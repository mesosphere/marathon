package mesosphere.marathon.api.v2.json

import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec }
import org.scalatest.GivenWhenThen

/**
  * Tests that test that the given JSON is rejected by the JSON schema.
  *
  * Since the JSON is not representable by an V2AppDefinition,
  * JSON is used directly.
  */
class V2AppDefinitionSchemaJSONTest extends MarathonSpec with GivenWhenThen {
  test("command health checks WITHOUT a nested value should be rejected") {
    Given("an app definition WITHOUT a nested value in command section of a health check")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "healthChecks": [
        |    {
        |      "protocol": "COMMAND",
        |      "command": "curl -f -X GET http://$HOST:$PORT0/health"
        |    }
        |  ]
        |}
      """.stripMargin

    Then("validation should fail")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
  }

  test("command health checks WITH a nested value should be accepted") {
    Given("an app definition WITH a nested value in command section of a health check")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "healthChecks": [
        |    {
        |      "protocol": "COMMAND",
        |      "command": { "value": "curl -f -X GET http://$HOST:$PORT0/health" }
        |    }
        |  ]
        |}
      """.stripMargin

    Then("validation should succeed")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = true)
  }
}
