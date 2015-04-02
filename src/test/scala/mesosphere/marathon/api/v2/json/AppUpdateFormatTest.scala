package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.AppUpdate
import org.scalatest.Matchers
import play.api.libs.json.{ Json, JsResultException }

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

  test("FromJSON should fail when 'cpus' is less than or equal to 0") {
    var json1 = Json.parse(""" { "id": "test", "cpus": 0.0 }""")
    a[JsResultException] shouldBe thrownBy { json1.as[AppUpdate] }

    val json2 = Json.parse(""" { "id": "test", "cpus": -1.0 }""")
    a[JsResultException] shouldBe thrownBy { json2.as[AppUpdate] }
  }
}
