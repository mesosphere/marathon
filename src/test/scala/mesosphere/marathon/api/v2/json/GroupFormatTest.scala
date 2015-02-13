package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.Group
import play.api.libs.json.Json

class GroupFormatTest extends MarathonSpec {
  import Formats._

  // regression test for #1176
  test("allow / as id") {
    val json = """{"id": "/"}"""

    assert(Json.parse(json).as[Group].id.isRoot)
  }
}
