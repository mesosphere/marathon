package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.AppDefinition
import org.scalatest.Matchers
import play.api.libs.json.{ Json, JsResultException }

class V2AppUpdateFormatTest extends MarathonSpec with Matchers {
  import Formats._

  // regression test for #1176
  test("should fail if id is /") {
    val json = """{"id": "/"}"""
    a[JsResultException] shouldBe thrownBy { Json.parse(json).as[V2AppUpdate] }
  }

  test("FromJSON should fail when using / as an id") {
    val json = Json.parse(""" { "id": "/" }""")
    a[JsResultException] shouldBe thrownBy { json.as[V2AppUpdate] }
  }

  test("FromJSON should not fail when 'cpus' is greater than 0") {
    val json = Json.parse(""" { "id": "test", "cpus": 0.0001 }""")
    noException should be thrownBy {
      json.as[V2AppUpdate]
    }
  }

  test("FromJSON should fail when 'cpus' is less than or equal to 0") {
    val json1 = Json.parse(""" { "id": "test", "cpus": 0.0 }""")
    a[JsResultException] shouldBe thrownBy { json1.as[V2AppUpdate] }

    val json2 = Json.parse(""" { "id": "test", "cpus": -1.0 }""")
    a[JsResultException] shouldBe thrownBy { json2.as[V2AppUpdate] }
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["production", "*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["production", "*"] }""")
    val appDef = json.as[V2AppUpdate]
    appDef.acceptedResourceRoles should equal(Some(Set("production", "*")))
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["*"] }""")
    val appDef = json.as[V2AppUpdate]
    appDef.acceptedResourceRoles should equal(Some(Set("*")))
  }

  test("FromJSON should fail when 'acceptedResourceRoles' is defined but empty") {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": [] }""")
    a[JsResultException] shouldBe thrownBy { json.as[V2AppUpdate] }
  }

}
