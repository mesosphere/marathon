package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2Group, V2GroupUpdate }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.Future
import scala.concurrent.duration._

class GroupsResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var groupsResource: GroupsResource = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    config.zkTimeoutDuration returns 5.seconds
    groupsResource = new GroupsResource(groupManager, auth.auth, auth.auth, config)
  }

  test("dry run update") {

    val app = V2AppDefinition(id = "/test/app".toRootPath, cmd = Some("test cmd"))
    val update = V2GroupUpdate(id = Some("/test".toRootPath), apps = Some(Set(app)))

    groupManager.group(update.groupId) returns Future.successful(None)

    val body = Json.stringify(Json.toJson(update)).getBytes
    val result = groupsResource.update("/test", force = false, dryRun = true, body, auth.request, auth.response)
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

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response
    val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

    When(s"the root is fetched from index")
    val root = groupsResource.root(req, resp)
    Then("we receive a NotAuthenticated response")
    root.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group by id is fetched from create")
    val group = groupsResource.group("/foo/bla", req, resp)
    Then("we receive a NotAuthenticated response")
    group.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the root group is created")
    val create = groupsResource.create(false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a NotAuthenticated response")
    create.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group is created")
    val createWithPath = groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a NotAuthenticated response")
    createWithPath.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the root group is updated")
    val updateRoot = groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a NotAuthenticated response")
    updateRoot.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group is updated")
    val update = groupsResource.update("", false, false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a NotAuthenticated response")
    update.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the root group is deleted")
    val deleteRoot = groupsResource.delete(false, req, resp)
    Then("we receive a NotAuthenticated response")
    deleteRoot.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group is deleted")
    val delete = groupsResource.delete("", false, req, resp)
    Then("we receive a NotAuthenticated response")
    delete.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response
    val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

    When(s"the root group is created")
    val create = groupsResource.create(false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a Not Authorized response")
    create.getStatus should be(auth.UnauthorizedStatus)

    When(s"the group is created")
    val createWithPath = groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a Not Authorized response")
    createWithPath.getStatus should be(auth.UnauthorizedStatus)

    When(s"the root group is updated")
    val updateRoot = groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a Not Authorized response")
    updateRoot.getStatus should be(auth.UnauthorizedStatus)

    When(s"the group is updated")
    val update = groupsResource.update("", false, false, body.getBytes("UTF-8"), req, resp)
    Then("we receive a Not Authorized response")
    update.getStatus should be(auth.UnauthorizedStatus)

    When(s"the root group is deleted")
    val deleteRoot = groupsResource.delete(false, req, resp)
    Then("we receive a Not Authorized response")
    deleteRoot.getStatus should be(auth.UnauthorizedStatus)

    When(s"the group is deleted")
    val delete = groupsResource.delete("", false, req, resp)
    Then("we receive a Not Authorized response")
    delete.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access with limited authorization gives a filtered apps listing") {
    Given("An authorized identity with limited ACL's")
    auth.authenticated = true
    auth.authorized = true
    auth.authFn = _.toString.startsWith("/visible")
    val group = Group(PathId.empty, Set(AppDefinition("/app1".toPath)), Set(
      Group("/visible".toPath, Set(AppDefinition("/visible/app1".toPath)), Set(
        Group("/visible/group".toPath, Set(AppDefinition("/visible/group/app1".toPath)))
      )),
      Group("/secure".toPath, Set(AppDefinition("/secure/app1".toPath)), Set(
        Group("/secure/group".toPath, Set(AppDefinition("/secure/group/app1".toPath)))
      )),
      Group("/other".toPath, Set(AppDefinition("/other/app1".toPath)), Set(
        Group("/other/group".toPath, Set(AppDefinition("/other/group/app1".toPath)))
      ))
    ))
    groupManager.group(PathId.empty) returns Future.successful(Some(group))

    When("The root group is fetched")
    val root = groupsResource.root(auth.request, auth.response)

    Then("The root group contains only entities according to ACL's")
    root.getStatus should be (200)
    val result = Json.parse(root.getEntity.asInstanceOf[String])
      .as[V2GroupUpdate]
      .copy(version = None)
      .apply(V2Group(Group.empty), Timestamp.now()).toGroup()
    result.transitiveApps should have size 2
    result.transitiveApps.map(_.id.toString) should be(Set("/visible/app1", "/visible/group/app1"))
    result.transitiveGroups should have size 3
  }

  test("Group Versions for root are transferred as simple json string array (Fix #2329)") {
    Given("Specific Group versions")
    val groupVersions = Seq(Timestamp.now(), Timestamp.now())
    groupManager.versions(any) returns Future.successful(groupVersions.toIterable)

    When("The versions are queried")
    val rootVersionsResponse = groupsResource.group("versions", auth.request, auth.response)

    Then("The versions are send as simple json array")
    rootVersionsResponse.getStatus should be (200)
    rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
  }

  test("Group Versions for path are transferred as simple json string array (Fix #2329)") {
    Given("Specific group versions")
    val groupVersions = Seq(Timestamp.now(), Timestamp.now())
    groupManager.versions(any) returns Future.successful(groupVersions.toIterable)

    When("The versions are queried")
    val rootVersionsResponse = groupsResource.group("/foo/bla/blub/versions", auth.request, auth.response)

    Then("The versions are send as simple json array")
    rootVersionsResponse.getStatus should be (200)
    rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
  }
}
