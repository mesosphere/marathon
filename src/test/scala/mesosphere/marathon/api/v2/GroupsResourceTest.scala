package mesosphere.marathon
package api.v2

import java.util.Collections

import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ TestAuthFixture, TestGroupManagerFixture }
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.raml.{ App, GroupUpdate }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.GroupCreation
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.Future
import scala.concurrent.duration._

class GroupsResourceTest extends AkkaUnitTest with GroupCreation {
  case class Fixture(
      config: MarathonConf = mock[MarathonConf],
      groupManager: GroupManager = mock[GroupManager],
      groupRepository: GroupRepository = mock[GroupRepository],
      auth: TestAuthFixture = new TestAuthFixture,
      groupInfo: GroupInfoService = mock[GroupInfoService],
      embed: java.util.Set[String] = Collections.emptySet[String]) {
    config.zkTimeoutDuration returns (patienceConfig.timeout.toMillis * 2).millis
    config.availableFeatures returns Set.empty
    config.defaultNetworkName returns new org.rogach.scallop.ScallopOption[String]("default_network_name") {}
    val groupsResource: GroupsResource = new GroupsResource(groupManager, groupInfo, config)(auth.auth, auth.auth, mat)
  }

  case class FixtureWithRealGroupManager(
      initialRoot: RootGroup = RootGroup.empty,
      groupInfo: GroupInfoService = mock[GroupInfoService],
      auth: TestAuthFixture = new TestAuthFixture) {
    val f = new TestGroupManagerFixture(initialRoot)
    val config: AllConf = f.config
    val groupRepository: GroupRepository = f.groupRepository
    val groupManager: GroupManager = f.groupManager
    val groupsResource: GroupsResource = new GroupsResource(groupManager, groupInfo, config)(auth.auth, auth.auth, mat)
  }

  "GroupsResource" should {
    "dry run update" in new FixtureWithRealGroupManager {
      Given("A real Group Manager with no groups")

      val app = App(id = "/test/app", cmd = Some("test cmd"))
      val update = GroupUpdate(id = Some("/test"), apps = Some(Set(app)))

      When("Doing a dry run update")
      val body = Json.stringify(Json.toJson(update)).getBytes
      val result = groupsResource.update("/test", force = false, dryRun = true, body, auth.request)
      val json = Json.parse(result.getEntity.toString)

      Then("The deployment plan is correct")
      val steps = (json \ "steps").as[Seq[JsObject]]
      assert(steps.size == 2)

      val firstStep = (steps.head \ "actions").as[Seq[JsObject]].head
      assert((firstStep \ "action").as[String] == "StartApplication")
      assert((firstStep \ "app").as[String] == "/test/app")

      val secondStep = (steps.last \ "actions").as[Seq[JsObject]].head
      assert((secondStep \ "action").as[String] == "ScaleApplication")
      assert((secondStep \ "app").as[String] == "/test/app")
    }

    "dry run update on an existing group" in new FixtureWithRealGroupManager {
      Given("A real Group Manager with no groups")
      val rootGroup = createRootGroup().makeGroup(PathId("/foo/bla"))

      val app = App(id = "/foo/bla/app", cmd = Some("test cmd"))
      val update = GroupUpdate(id = Some("/foo/bla"), apps = Some(Set(app)))

      When("Doing a dry run update")
      val body = Json.stringify(Json.toJson(update)).getBytes
      val result = groupsResource.update("/foo/bla", force = false, dryRun = true, body, auth.request)
      val json = Json.parse(result.getEntity.toString)

      Then("The deployment plan is correct")
      val steps = (json \ "steps").as[Seq[JsObject]]
      assert(steps.size == 2)

      val firstStep = (steps.head \ "actions").as[Seq[JsObject]].head
      assert((firstStep \ "action").as[String] == "StartApplication")
      assert((firstStep \ "app").as[String] == "/foo/bla/app")

      val secondStep = (steps.last \ "actions").as[Seq[JsObject]].head
      assert((secondStep \ "action").as[String] == "ScaleApplication")
      assert((secondStep \ "app").as[String] == "/foo/bla/app")
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

      groupManager.rootGroup() returns createRootGroup()

      When("the root is fetched from index")
      val root = groupsResource.root(req, embed)
      Then("we receive a NotAuthenticated response")
      root.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group by id is fetched from create")
      val rootGroup = groupsResource.group("/foo/bla", embed, req)
      Then("we receive a NotAuthenticated response")
      rootGroup.getStatus should be(auth.NotAuthenticatedStatus)

      When("the root group is created")
      val create = groupsResource.create(false, body.getBytes("UTF-8"), req)
      Then("we receive a NotAuthenticated response")
      create.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group is created")
      val createWithPath = groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req)
      Then("we receive a NotAuthenticated response")
      createWithPath.getStatus should be(auth.NotAuthenticatedStatus)

      When("the root group is updated")
      val updateRoot = groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req)
      Then("we receive a NotAuthenticated response")
      updateRoot.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group is updated")
      val update = groupsResource.update("", false, false, body.getBytes("UTF-8"), req)
      Then("we receive a NotAuthenticated response")
      update.getStatus should be(auth.NotAuthenticatedStatus)

      When("the root group is deleted")
      val deleteRoot = groupsResource.delete(false, req)
      Then("we receive a NotAuthenticated response")
      deleteRoot.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group is deleted")
      val delete = groupsResource.delete("", false, req)
      Then("we receive a NotAuthenticated response")
      delete.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied if the resource exists" in new FixtureWithRealGroupManager {
      Given("A real group manager with one app")
      val app = AppDefinition("/a".toRootPath)
      val rootGroup = createRootGroup(apps = Map(app.id -> app))

      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

      When("the root group is created")
      val create = groupsResource.create(false, body.getBytes("UTF-8"), req)
      Then("we receive a Not Authorized response")
      create.getStatus should be(auth.UnauthorizedStatus)

      When("the group is created")
      val createWithPath = groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req)
      Then("we receive a Not Authorized response")
      createWithPath.getStatus should be(auth.UnauthorizedStatus)

      When("the root group is updated")
      val updateRoot = groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req)
      Then("we receive a Not Authorized response")
      updateRoot.getStatus should be(auth.UnauthorizedStatus)

      When("the group is updated")
      val update = groupsResource.update("", false, false, body.getBytes("UTF-8"), req)
      Then("we receive a Not Authorized response")
      update.getStatus should be(auth.UnauthorizedStatus)

      When("the root group is deleted")
      val deleteRoot = groupsResource.delete(false, req)
      Then("we receive a Not Authorized response")
      deleteRoot.getStatus should be(auth.UnauthorizedStatus)

      When("the group is deleted")
      val delete = groupsResource.delete("", false, req)
      Then("we receive a Not Authorized response")
      delete.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to root group without authentication is allowed" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      groupInfo.selectGroup(any, any, any, any) returns Future.successful(None)

      When("the root is fetched from index")
      val root = groupsResource.root(req, embed)

      Then("the request is successful")
      root.getStatus should be(200)
    }

    "authenticated delete without authorization leads to a 404 if the resource doesn't exist" in new FixtureWithRealGroupManager {
      Given("A real group manager with no apps")

      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      When("the group is deleted")
      Then("we get a 404")
      // FIXME (gkleiman): this leads to an ugly stack trace
      intercept[UnknownGroupException] { groupsResource.delete("/foo", false, req) }
    }

    "Group Versions for root are transferred as simple json string array (Fix #2329)" in new Fixture {
      Given("Specific Group versions")
      val groupVersions = Seq(Timestamp.now(), Timestamp.now())
      groupManager.versions(PathId.empty) returns Source(groupVersions)
      groupManager.group(PathId.empty) returns Some(createGroup(PathId.empty))

      When("The versions are queried")
      val rootVersionsResponse = groupsResource.group("versions", embed, auth.request)

      Then("The versions are send as simple json array")
      rootVersionsResponse.getStatus should be (200)
      rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
    }

    "Group Versions for path are transferred as simple json string array (Fix #2329)" in new Fixture {
      Given("Specific group versions")
      val groupVersions = Seq(Timestamp.now(), Timestamp.now())
      groupManager.versions(any) returns Source(groupVersions)
      groupManager.versions("/foo/bla/blub".toRootPath) returns Source(groupVersions)
      groupManager.group("/foo/bla/blub".toRootPath) returns Some(createGroup("/foo/bla/blub".toRootPath))

      When("The versions are queried")
      val rootVersionsResponse = groupsResource.group("/foo/bla/blub/versions", embed, auth.request)

      Then("The versions are send as simple json array")
      rootVersionsResponse.getStatus should be (200)
      rootVersionsResponse.getEntity should be(Json.toJson(groupVersions).toString())
    }

    "Creation of a group with same path as an existing app should be prohibited (fixes #3385)" in new FixtureWithRealGroupManager(
      initialRoot = {
      val app = AppDefinition("/group/app".toRootPath)
      createRootGroup(groups = Set(createGroup("/group".toRootPath, Map(app.id -> app))))
    }
    ) {
      Given("A real group manager with one app")

      When("creating a group with the same path existing app")
      val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("/group/app"))))

      Then("we get a 409")
      intercept[ConflictingChangeException] { groupsResource.create(false, body.getBytes, auth.request) }
    }

    "Creation of a group with same path as an existing group should be prohibited" in
      new FixtureWithRealGroupManager(initialRoot = createRootGroup(groups = Set(createGroup("/group".toRootPath)))) {
        Given("A real group manager with one app")

        val rootGroup = createRootGroup(groups = Set(createGroup("/group".toRootPath)))

        When("creating a group with the same path existing app")
        val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("/group"))))

        Then("we get a 409")
        intercept[ConflictingChangeException] { groupsResource.create(false, body.getBytes, auth.request) }
      }
  }
}
