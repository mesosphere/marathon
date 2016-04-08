package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.ResourceRole
import org.scalatest.Matchers
import play.api.libs.json.{ JsResultException, Json }

class AppUpdateFormatTest extends MarathonSpec with Matchers {
  import Formats._

  // regression test for #1176
  test("should fail if id is /") {
    val json = """{"id": "/"}"""
    a[JsResultException] shouldBe thrownBy { Json.parse(json).as[AppUpdate] }
  }

  test("FromJSON should fail when using / as an id") {
    val json = Json.parse(""" { "id": "/" }""")
    a[JsResultException] shouldBe thrownBy { json.as[AppUpdate] }
  }

  test("FromJSON should not fail when 'cpus' is greater than 0") {
    val json = Json.parse(""" { "id": "test", "cpus": 0.0001 }""")
    noException should be thrownBy {
      json.as[AppUpdate]
    }
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["production", "*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["production", "*"] }""")
    val appUpdate = json.as[AppUpdate]
    appUpdate.acceptedResourceRoles should equal(Some(Set("production", ResourceRole.Unreserved)))
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["*"] }""")
    val appUpdate = json.as[AppUpdate]
    appUpdate.acceptedResourceRoles should equal(Some(Set(ResourceRole.Unreserved)))
  }

  test("FromJSON should fail when 'acceptedResourceRoles' is defined but empty") {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": [] }""")
    a[JsResultException] shouldBe thrownBy { json.as[AppUpdate] }
  }

  // Regression test for #3140
  test("FromJSON should set healthCheck portIndex to 0 when neither port nor portIndex are set") {
    val json = Json.parse(""" { "id": "test", "healthChecks": [{ "path": "/", "protocol": "HTTP" }] } """)
    val appUpdate = json.as[AppUpdate]
    appUpdate.healthChecks.get.head.portIndex should equal(Some(0))
  }

}
