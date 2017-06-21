package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper

/**
  * Tests that test that the given JSON is rejected by the JSON schema.
  *
  * Since the JSON is not representable by an AppDefinition,
  * JSON is used directly.
  */
class AppDefinitionSchemaJSONTest extends UnitTest {

  "AppDefinitionJsonSchema" should {
    "command health checks WITHOUT a nested value should be rejected" in {
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

    "command health checks WITH a nested value should be accepted" in {
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

    "discoveryInfo ports WITH a correct protocol should be accepted" in {
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

    "discoveryInfo ports WITH an incorrect protocol should be rejected" in {
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

      Then("validation should fail")
      MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
    }

    "command constrains WITH array of arrays of strings should succeed" in {
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

    "command constrains WITH unknown constraint should fail" in {
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

    "command constrains WITH array of strings and boolean should fail" in {
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

    "command constrains WITH array of one string should fail" in {
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

    "portMapping with valid networkNames should succeed" in {
      Given("portMapping with invalid networkNames")
      val json =
        """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "container": {
        |    "type": "MESOS",
        |    "portMappings": [
        |      {"hostPort": 0, "networkNames": ["valid-name"]}
        |    ]
        |  }
        |}
      """.stripMargin

      Then("validation should succeed")
      MarathonTestHelper.validateJsonSchemaForString(json, valid = true)
    }

    "portMapping with invalid network name should fail" in {
      Given("portMapping with invalid networkNames")
      val json =
        """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "container": {
        |    "type": "MESOS",
        |    "portMappings": [
        |      {"hostPort": 0, "networkNames": ["invalid name"]}
        |    ]
        |  }
        |}
      """.stripMargin

      Then("validation should fail")
      MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
    }

    "portMapping with non-array should fail" in {
      Given("portMapping with invalid networkNames")
      val json =
        """
        |{
        |  "id": "/test",
        |  "cmd": "echo hi",
        |  "container": {
        |    "type": "MESOS",
        |    "portMappings": [
        |      {"hostPort": 0, "networkNames": "invalid name"}
        |    ]
        |  }
        |}
      """.stripMargin

      Then("validation should fail")
      MarathonTestHelper.validateJsonSchemaForString(json, valid = false)
    }
  }
}
