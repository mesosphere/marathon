package mesosphere.marathon.api.v2

import java.util.Collections

import mesosphere.marathon.api.{ TestGroupManagerFixture, TestAuthFixture }
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.GroupUpdate
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ ConflictingChangeException, UnknownGroupException, MarathonConf, MarathonSpec }
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.Future
import scala.concurrent.duration._

class GroupsResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {
  test("dry run update") {
    Given("A real Group Manager with no groups")
    useRealGroupManager()
    groupRepository.group(GroupRepository.zkRootName) returns Future.successful(Some(Group.empty))

    val app = AppDefinition(id = "/test/app".toRootPath, cmd = Some("test cmd"))
    val update = GroupUpdate(id = Some("/test".toRootPath), apps = Some(Set(app)))

    When("Doing a dry run update")
    val body = Json.stringify(Json.toJson(update)).getBytes
    val result = groupsResource.update("/test", force = false, dryRun = true, body, auth.request)
    val json = Json.parse(result.getEntity.toString)

    Then("The deployment plan is correct")
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
    val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

    groupManager.rootGroup() returns Future.successful(Group(PathId.empty))

    When(s"the root is fetched from index")
    val root = groupsResource.root(req, embed)
    Then("we receive a NotAuthenticated response")
    root.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group by id is fetched from create")
    val group = groupsResource.group("/foo/bla", embed, req)
    Then("we receive a NotAuthenticated response")
    group.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the root group is created")
    val create = groupsResource.create(false, body.getBytes("UTF-8"), req)
    Then("we receive a NotAuthenticated response")
    create.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group is created")
    val createWithPath = groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req)
    Then("we receive a NotAuthenticated response")
    createWithPath.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the root group is updated")
    val updateRoot = groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req)
    Then("we receive a NotAuthenticated response")
    updateRoot.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group is updated")
    val update = groupsResource.update("", false, false, body.getBytes("UTF-8"), req)
    Then("we receive a NotAuthenticated response")
    update.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the root group is deleted")
    val deleteRoot = groupsResource.delete(false, req)
    Then("we receive a NotAuthenticated response")
    deleteRoot.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the group is deleted")
    val delete = groupsResource.delete("", false, req)
    Then("we receive a NotAuthenticated response")
    delete.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied if the resource exists") {
    Given("A real group manager with one app")
    useRealGroupManager()
    val group = Group(PathId.empty, apps = Set(AppDefinition("/a".toRootPath)))
    groupRepository.group(GroupRepository.zkRootName) returns Future.successful(Some(group))
    groupRepository.rootGroup returns Future.successful(Some(group))

    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

    When(s"the root group is created")
    val create = groupsResource.create(false, body.getBytes("UTF-8"), req)
    Then("we receive a Not Authorized response")
    create.getStatus should be(auth.UnauthorizedStatus)

    When(s"the group is created")
    val createWithPath = groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req)
    Then("we receive a Not Authorized response")
    createWithPath.getStatus should be(auth.UnauthorizedStatus)

    When(s"the root group is updated")
    val updateRoot = groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req)
    Then("we receive a Not Authorized response")
    updateRoot.getStatus should be(auth.UnauthorizedStatus)

    When(s"the group is updated")
    val update = groupsResource.update("", false, false, body.getBytes("UTF-8"), req)
    Then("we receive a Not Authorized response")
    update.getStatus should be(auth.UnauthorizedStatus)

    When(s"the root group is deleted")
    val deleteRoot = groupsResource.delete(false, req)
    Then("we receive a Not Authorized response")
    deleteRoot.getStatus should be(auth.UnauthorizedStatus)

    When(s"the group is deleted")
    val delete = groupsResource.delete("", false, req)
    Then("we receive a Not Authorized response")
    delete.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to root group without authentication is allowed") {
    Given("An unauthenticated request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    groupInfo.selectGroup(any, any, any, any) returns Future.successful(None)

    When(s"the root is fetched from index")
    val root = groupsResource.root(req, embed)

    Then("the request is successful")
    root.getStatus should be(200)
  }

  test("authenticated delete without authorization leads to a 404 if the resource doesn't exist") {
    Given("A real group manager with no apps")
    useRealGroupManager()
    groupRepository.group("/") returns Future.successful(None)
    groupRepository.group(GroupRepository.zkRootName) returns Future.successful(Some(Group.empty))
    groupRepository.rootGroup returns Future.successful(Some(Group.empty))

    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    When(s"the group is deleted")
    Then("we get a 404")
    // FIXME (gkleiman): this leads to an ugly stack trace
    intercept[UnknownGroupException] { groupsResource.delete("/foo", false, req) }
  }

  test("Group Versions for root are transferred as simple json string array (Fix #2329)") {
    Given("Specific Group versions")
    val groupVersions = Seq(Timestamp.now(), Timestamp.now())
    groupManager.versions(PathId.empty) returns Future.successful(groupVersions.toIterable)
    groupManager.group(PathId.empty) returns Future.successful(Some(Group(PathId.empty)))

    When("The versions are queried")
    val rootVersionsResponse = groupsResource.group("versions", embed, auth.request)

    Then("The versions are send as simple json array")
    rootVersionsResponse.getStatus should be (200)
    rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
  }

  test("Group Versions for path are transferred as simple json string array (Fix #2329)") {
    Given("Specific group versions")
    val groupVersions = Seq(Timestamp.now(), Timestamp.now())
    groupManager.versions(any) returns Future.successful(groupVersions.toIterable)
    groupManager.versions("/foo/bla/blub".toRootPath) returns Future.successful(groupVersions.toIterable)
    groupManager.group("/foo/bla/blub".toRootPath) returns Future.successful(Some(Group("/foo/bla/blub".toRootPath)))

    When("The versions are queried")
    val rootVersionsResponse = groupsResource.group("/foo/bla/blub/versions", embed, auth.request)

    Then("The versions are send as simple json array")
    rootVersionsResponse.getStatus should be (200)
    rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
  }

  test("Creation of a group with same path as an existing app should be prohibited (fixes #3385)") {
    Given("A real group manager with one app")
    useRealGroupManager()
    val group = Group("/group".toRootPath, apps = Set(AppDefinition("/group/app".toRootPath)))
    groupRepository.group(GroupRepository.zkRootName) returns Future.successful(Some(group))
    groupRepository.rootGroup returns Future.successful(Some(group))

    When("creating a group with the same path existing app")
    val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("/group/app".toRootPath))))

    Then("we get a 409")
    intercept[ConflictingChangeException] { groupsResource.create(false, body.getBytes, auth.request) }
  }

  test("Creation of a group with same path as an existing group should be prohibited") {
    Given("A real group manager with one app")
    useRealGroupManager()
    val group = Group("/group".toRootPath)
    groupRepository.group(GroupRepository.zkRootName) returns Future.successful(Some(group))
    groupRepository.rootGroup returns Future.successful(Some(group))

    When("creating a group with the same path existing app")
    val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("/group".toRootPath))))

    Then("we get a 409")
    intercept[ConflictingChangeException] { groupsResource.create(false, body.getBytes, auth.request) }
  }

  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var groupRepository: GroupRepository = _
  var groupsResource: GroupsResource = _
  var auth: TestAuthFixture = _
  var groupInfo: GroupInfoService = _
  val embed: java.util.Set[String] = Collections.emptySet()

  before {
    auth = new TestAuthFixture
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    groupInfo = mock[GroupInfoService]
    groupsResource = new GroupsResource(groupManager, groupInfo, auth.auth, auth.auth, config)

    config.zkTimeoutDuration returns 1.second
  }

  private[this] def useRealGroupManager(): Unit = {
    val f = new TestGroupManagerFixture()
    config = f.config
    groupRepository = f.groupRepository
    groupManager = f.groupManager

    config.zkTimeoutDuration returns 1.second

    groupsResource = new GroupsResource(groupManager, groupInfo, auth.auth, auth.auth, config)
  }
}
