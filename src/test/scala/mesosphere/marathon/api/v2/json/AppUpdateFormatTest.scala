package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.AppUpdate
import play.api.libs.json.{ Json, JsResultException }

class AppUpdateFormatTest extends MarathonSpec {
  import Formats._

  // regression test for #1176
  test("should fail if id is /") {
    val json = """{"id": "/"}"""
    intercept[JsResultException] {
      Json.parse(json).as[AppUpdate]
    }
  }
}
