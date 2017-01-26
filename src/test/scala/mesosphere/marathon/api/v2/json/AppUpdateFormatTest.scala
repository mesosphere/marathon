package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.state.{ KillSelection, ResourceRole }
import play.api.libs.json.{ JsResultException, Json }

class AppUpdateFormatTest extends UnitTest {
  import Formats._

  "AppUpdateFormats" should {
    // regression test for #1176
    "should fail if id is /" in {
      val json = """{"id": "/"}"""
      a[JsResultException] shouldBe thrownBy { Json.parse(json).as[AppUpdate] }
    }

    "FromJSON should fail when using / as an id" in {
      val json = Json.parse(""" { "id": "/" }""")
      a[JsResultException] shouldBe thrownBy { json.as[AppUpdate] }
    }

    "FromJSON should not fail when 'cpus' is greater than 0" in {
      val json = Json.parse(""" { "id": "test", "cpus": 0.0001 }""")
      noException should be thrownBy {
        json.as[AppUpdate]
      }
    }

    """FromJSON should parse "acceptedResourceRoles": ["production", "*"] """ in {
      val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["production", "*"] }""")
      val appUpdate = json.as[AppUpdate]
      appUpdate.acceptedResourceRoles should equal(Some(Set("production", ResourceRole.Unreserved)))
    }

    """FromJSON should parse "acceptedResourceRoles": ["*"] """ in {
      val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["*"] }""")
      val appUpdate = json.as[AppUpdate]
      appUpdate.acceptedResourceRoles should equal(Some(Set(ResourceRole.Unreserved)))
    }

    "FromJSON should fail when 'acceptedResourceRoles' is defined but empty" in {
      val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": [] }""")
      a[JsResultException] shouldBe thrownBy { json.as[AppUpdate] }
    }

    "FromJSON should parse kill selection" in {
      val json = Json.parse(""" { "id": "test", "killSelection": "OLDEST_FIRST" }""")
      val appUpdate = json.as[AppUpdate]
      appUpdate.killSelection should be(Some(KillSelection.OldestFirst))
    }

    "FromJSON should default to empty kill selection" in {
      val json = Json.parse(""" { "id": "test" }""")
      val appUpdate = json.as[AppUpdate]
      appUpdate.killSelection should not be 'defined
    }

    // Regression test for #3140
    "FromJSON should set healthCheck portIndex to 0 when neither port nor portIndex are set" in {
      val json = Json.parse(""" { "id": "test", "healthChecks": [{ "path": "/", "protocol": "HTTP" }] } """)
      val appUpdate = json.as[AppUpdate]
      appUpdate.healthChecks.get.head.asInstanceOf[MarathonHttpHealthCheck].portIndex should equal(Some(PortReference(0)))
    }
  }
}
