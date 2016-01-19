package mesosphere.marathon.api.v2.json

import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec }
import org.scalatest.GivenWhenThen

/**
  * Tests that test that the given JSON is rejected by the JSON schema.
  *
  * Since the JSON is not representable by an AppDefinition,
  * JSON is used directly.
  */
class AppDefinitionSchemaJSONTest extends MarathonSpec with GivenWhenThen {
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

  test("discoveryInfo ports WITH a correct protocol should be accepted") {
    Given("an app definition WITH a discovery info with a TCP and a UDP port")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "ipAddress": {
        |    "discovery": {
        |      "ports": [
        |        {"name": "dns", "number": 53, "protocol": "udp"},
        |        {"name": "http", "number": 80, "protocol": "tcp"}
        |      ]
        |    }
        |  }
        |}
      """.stripMargin

    Then("validation should succeed")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = true)
  }

  test("discoveryInfo ports WITH an incorrect protocol should be rejected") {
    Given("an app definition WITH a discovery info with a FOO protocol port")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "ipAddress": {
        |    "discovery": {
        |      "ports": [
        |        {"name": "dns", "number": 53, "protocol": "foo"}
        |      ]
        |    }
        |  }
        |}
      """.stripMargin

    Then("validation should succeed")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
  }

  test("command constrains WITH array of arrays of strings should succeed") {
    Given("constrains WITH nested arrays of strings")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "constraints": [
        |    ["rack_id", "LIKE", "rack-[1-3]"],
        |    ["hostname", "UNIQUE"]
        |  ]
        |}
      """.stripMargin

    Then("validation should succeed")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = true)
  }

  test("command constrains WITH unknown constraint should fail") {
    Given("constrains WITH nested arrays of strings")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "constraints": [
        |    ["rack_id", "NOT LIKE", "rack-[1-3]"],
        |    ["hostname", "UNIQUE", 1]
        |  ]
        |}
      """.stripMargin

    Then("validation should succeed")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
  }

  test("command constrains WITH array of strings and boolean should fail") {
    Given("constrains WITH nested array of something")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "constraints": [["lb", "CLUSTER", true]]
        |}
      """.stripMargin

    Then("validation should fail")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
  }

  test("command constrains WITH array of one string should fail") {
    Given("constrains WITH nested array of something")
    val json =
      """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "constraints": [["hostname"]]
        |}
      """.stripMargin

    Then("validation should fail")
    MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
  }
}
