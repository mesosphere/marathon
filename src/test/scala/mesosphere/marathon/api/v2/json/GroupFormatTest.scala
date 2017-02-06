package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{ AppDefinition, Group, PathId }
import mesosphere.marathon.test.GroupCreation
import play.api.libs.json.Json

class GroupFormatTest extends UnitTest with GroupCreation {

  import Formats._

  "GroupFormat" should {
    // regression test for #1176
    "allow / as id" in {
      val json = """{"id": "/"}"""

      assert(Json.parse(json).as[Group].id.isRoot)
    }

    // regression test for MARATHON-1294
    "a group with pod can be read by ignoring pods" in {
      val pod = PodDefinition(PathId("/pod"), containers = Seq(MesosContainer("test", resources = Resources())))
      val app = AppDefinition(PathId("/app"))
      val group = createGroup(PathId("/"), apps = Map(app.id -> app), pods = Map(pod.id -> pod))
      val json = Json.toJson(group)
      val readGroup = json.as[Group]
      //apps can be read
      readGroup.apps should have size 1
      readGroup.apps.head._1 should be(app.id)
      //pods will not be read
      readGroup.pods should have size 0
    }
  }
}