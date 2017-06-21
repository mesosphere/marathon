package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.v2.{ AppNormalization, AppsResource }
import mesosphere.marathon.raml.AppUpdate
import mesosphere.marathon.state.ResourceRole
import play.api.libs.json.Json

class AppUpdateFormatTest extends UnitTest {

  def normalizedAndValidated(appUpdate: AppUpdate): AppUpdate =
    AppsResource.appUpdateNormalization(
      AppsResource.NormalizationConfig(
        Set.empty,
        AppNormalization.Configuration(None, "mesos-bridge-name"))).normalized(appUpdate)

  def fromJson(json: String): AppUpdate =
    normalizedAndValidated(Json.parse(json).as[AppUpdate])

  "AppUpdateFormats" should {
    // regression test for #1176
    "should fail if id is /" in {
      val json = """{"id": "/"}"""
      a[ValidationFailedException] shouldBe thrownBy {
        fromJson(json)
      }
    }

    "FromJSON should not fail when 'cpus' is greater than 0" in {
      val json = """ { "id": "test", "cpus": 0.0001 }"""
      noException should be thrownBy {
        fromJson(json)
      }
    }

    """FromJSON should parse "acceptedResourceRoles": ["production", "*"] """ in {
      val json = """ { "id": "test", "acceptedResourceRoles": ["production", "*"] }"""
      val appUpdate = fromJson(json)
      appUpdate.acceptedResourceRoles should equal(Some(Set("production", ResourceRole.Unreserved)))
    }

    """FromJSON should parse "acceptedResourceRoles": ["*"] """ in {
      val json = """ { "id": "test", "acceptedResourceRoles": ["*"] }"""
      val appUpdate = fromJson(json)
      appUpdate.acceptedResourceRoles should equal(Some(Set(ResourceRole.Unreserved)))
    }

    "FromJSON should fail when 'acceptedResourceRoles' is defined but empty" in {
      val json = """ { "id": "test", "acceptedResourceRoles": [] }"""
      a[ValidationFailedException] shouldBe thrownBy {
        fromJson(json)
      }
    }

    "FromJSON should parse kill selection" in {
      val json = Json.parse(""" { "id": "test", "killSelection": "OLDEST_FIRST" }""")
      val appUpdate = json.as[AppUpdate]
      appUpdate.killSelection should be(Some(raml.KillSelection.OldestFirst))
    }

    "FromJSON should default to empty kill selection" in {
      val json = Json.parse(""" { "id": "test" }""")
      val appUpdate = json.as[AppUpdate]
      appUpdate.killSelection should not be 'defined
    }

    // Regression test for #3140
    "FromJSON should set healthCheck portIndex to 0 when neither port nor portIndex are set" in {
      val json = """ { "id": "test", "healthChecks": [{ "path": "/", "protocol": "HTTP" }] } """
      val appUpdate = fromJson(json)
      appUpdate.healthChecks.get.head.portIndex should equal(Some(0))
    }
  }
}
