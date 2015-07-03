package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2GroupUpdate }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, GroupManager }
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import org.mockito.Mockito.when
import org.scalatest.Matchers
import play.api.libs.json.{ JsObject, Json }
import mesosphere.marathon.api.v2.json.Formats._

import scala.concurrent.Future
import scala.concurrent.duration._

class GroupsResourceTest extends MarathonSpec with Matchers {
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var groupsResource: GroupsResource = _

  before {
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]

    when(config.zkTimeoutDuration).thenReturn(5.seconds)

    groupsResource = new GroupsResource(groupManager, config)
  }

  test("dry run update") {

    val app = V2AppDefinition(id = "/test/app".toRootPath, cmd = Some("test cmd"))
    val update = V2GroupUpdate(id = Some("/test".toRootPath), apps = Some(Set(app)))

    when(groupManager.group(update.groupId)).thenReturn(Future.successful(None))

    val body = Json.stringify(Json.toJson(update)).getBytes
    val result = groupsResource.update("/test", force = false, dryRun = true, body)
    val json = Json.parse(result.getEntity.toString)

    val steps = (json \ "steps").as[Seq[JsObject]]
    assert(steps.size == 2)

    val firstStep = (steps.head \ "actions").as[Seq[JsObject]].head
    assert((firstStep \ "type").as[String] == "StartApplication")
    assert((firstStep \ "app").as[String] == "/test/app")

    val secondStep = (steps.last \ "actions").as[Seq[JsObject]].head
    assert((secondStep \ "type").as[String] == "ScaleApplication")
    assert((secondStep \ "app").as[String] == "/test/app")
  }
}
