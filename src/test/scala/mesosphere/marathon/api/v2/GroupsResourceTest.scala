package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2GroupUpdate }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ GroupManager, Timestamp }
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.util.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.Future
import scala.concurrent.duration._

class GroupsResourceTest extends MarathonSpec with Matchers with GivenWhenThen with Mockito {
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var groupsResource: GroupsResource = _

  before {
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]

    config.zkTimeoutDuration returns 5.seconds

    groupsResource = new GroupsResource(groupManager, config)
  }

  test("dry run update") {

    val app = V2AppDefinition(id = "/test/app".toRootPath, cmd = Some("test cmd"))
    val update = V2GroupUpdate(id = Some("/test".toRootPath), apps = Some(Set(app)))

    groupManager.group(update.groupId) returns Future.successful(None)

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

  test("Group Versions for root are transferred as simple json string array (Fix #2329)") {
    Given("Specific Group versions")
    val groupVersions = Seq(Timestamp.now(), Timestamp.now())
    groupManager.versions(any) returns Future.successful(groupVersions.toIterable)

    When("The versions are queried")
    val rootVersionsResponse = groupsResource.group("versions")

    Then("The versions are send as simple json array")
    rootVersionsResponse.getStatus should be (200)
    rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
  }

  test("Group Versions for path are transferred as simple json string array (Fix #2329)") {
    Given("Specific group versions")
    val groupVersions = Seq(Timestamp.now(), Timestamp.now())
    groupManager.versions(any) returns Future.successful(groupVersions.toIterable)

    When("The versions are queried")
    val rootVersionsResponse = groupsResource.group("/foo/bla/blub/versions")

    Then("The versions are send as simple json array")
    rootVersionsResponse.getStatus should be (200)
    rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
  }
}
