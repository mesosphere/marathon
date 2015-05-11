package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import play.api.libs.json.Json

class V2GroupFormatTest extends MarathonSpec {
  import Formats._

  // regression test for #1176
  test("allow / as id") {
    val json = """{"id": "/"}"""

    assert(Json.parse(json).as[V2Group].id.isRoot)
  }
}
