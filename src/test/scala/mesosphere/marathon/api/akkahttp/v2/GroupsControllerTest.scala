package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.Uri.{ Path, Query }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.{ GroupApiService, TestAuthFixture, TestGroupManagerFixture }
import mesosphere.marathon.api.akkahttp.{ Headers, Rejections }
import mesosphere.marathon.core.appinfo.{ AppInfo, GroupInfo, GroupInfoService }
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.ContainerNetwork
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PathId, RootGroup, Timestamp }
import mesosphere.marathon.test.{ GroupCreation, SettableClock }
import org.scalatest.Inside
import play.api.libs.json.{ Json, _ }

import scala.collection.immutable.Seq
import scala.concurrent.Future

class GroupsControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging with GroupCreation with ResponseMatchers {

  implicit val identity: Identity = new Identity {}

  "Group detail" should {

    // Entity not found test cases
    {
      val f = new FixtureWithRealGroupManager(authorized = false)
      behave like unknownEntity(forRoute = f.groupsController.route, withRequest = Delete("/groupname"), withMessage = "Group '/groupname' does not exist")

      val infoService = mock[GroupInfoService]
      infoService.selectGroup(any, any, any, any) returns Future.successful(None)
      infoService.selectGroupVersion(any, any, any, any) returns Future.successful(None)
      val g = new Fixture(infoService = infoService)

      behave like unknownEntity(forRoute = g.groupsController.route, withRequest = Get("/unknown-group"), withMessage = "Group '/unknown-group' does not exist")
      behave like unknownEntity(forRoute = g.groupsController.route, withRequest = Get("/groupname/versions/2017-10-30T16:08:53.852Z"), withMessage = "Group '/groupname' does not exist in version 2017-10-30T16:08:53.852Z")
    }

    // Unauthenticated access test cases
    {
      val controller = Fixture(authenticated = false).groupsController
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./).addHeader(Accept(MediaTypes.`text/plain`)))
    }

    "returns group detail" in {
      val infoService = mock[GroupInfoService]
      infoService.selectGroup(eq(PathId("/test/group")), any, any, any) returns Future.successful(Some(GroupInfo(createGroup(PathId("/test/group")), None, None, None)))
      val f = new Fixture(infoService = infoService)

      Get(Uri./.withPath(Path("/test/group"))) ~> f.groupsController.route ~> check {
        status should be(StatusCodes.OK)
        (Json.parse(responseAs[String]) \ "id").get shouldEqual JsString("/test/group")
      }
    }

    "returns empty group if root and no group found" in {
      val infoService = mock[GroupInfoService]
      infoService.selectGroup(eq(PathId.empty), any, any, any) returns Future.successful(None)
      val f = new Fixture(infoService = infoService)

      Get(Uri./.withPath(Path("/"))) ~> f.groupsController.route ~> check {
        status should be(StatusCodes.OK)
        (Json.parse(responseAs[String]) \ "id").get shouldEqual JsString("/")
      }
    }
  }

  "List versions" should {
    {
      val controller = Fixture(authenticated = false).groupsController
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./.withPath(Path("versions"))).addHeader(Accept(MediaTypes.`text/plain`)))
    }

    "list all versions of given group" in {
      val clock = new SettableClock()
      val groupManager = mock[GroupManager]
      val groupVersions = Seq(clock.now(), clock.now())
      groupManager.versions("groupname".toRootPath) returns Source(groupVersions)
      groupManager.group("groupname".toRootPath) returns Some(createGroup(PathId.empty))
      val f = new Fixture(groupManager = groupManager)

      Get(Uri./.withPath(Path("/groupname/versions"))) ~> f.groupsController.route ~> check {
        responseAs[String] should be ("""[ "2015-04-09T12:30:00.000Z", "2015-04-09T12:30:00.000Z" ]""")
      }
    }

    "list all versions of root group" in {
      val clock = new SettableClock()
      val groupManager = mock[GroupManager]
      val groupVersions = Seq(clock.now())
      groupManager.versions(PathId.empty) returns Source(groupVersions)
      groupManager.group(PathId.empty) returns Some(createGroup(PathId.empty))
      val f = new Fixture(groupManager = groupManager)

      Get(Uri./.withPath(Path("/versions"))) ~> f.groupsController.route ~> check {
        responseAs[String] should be ("""[ "2015-04-09T12:30:00.000Z" ]""")
      }
    }
  }

  "Version detail" should {

    "show app in a given version" in {
      val infoService = mock[GroupInfoService]
      infoService.selectGroupVersion(any, any, any, any) returns Future.successful(Some(GroupInfo(createGroup(PathId("/test/group")), None, None, None)))
      val f = new Fixture(infoService = infoService)

      Get(Uri./.withPath(Path("/test/group/versions/2017-10-30T16:08:53.852Z"))) ~> f.groupsController.route ~> check {
        (Json.parse(responseAs[String]) \ "id").get shouldEqual JsString("/test/group")
      }
    }

    "show app in a given version for root group" in {
      val infoService = mock[GroupInfoService]
      infoService.selectGroupVersion(eq(PathId.empty), any, any, any) returns Future.successful(Some(GroupInfo(createGroup(PathId.empty), None, None, None)))
      val f = new Fixture(infoService = infoService)

      Get(Uri./.withPath(Path("/versions/2017-10-30T16:08:53.852Z"))) ~> f.groupsController.route ~> check {
        Json.parse(responseAs[String]) should have (
          id(PathId("/"))
        )
      }
    }
  }

  "Create a group" should {
    "fail with rejection for group that already exists" in {
      val groupManager = mock[GroupManager]
      groupManager.rootGroup() returns createRootGroup()
      val f = new Fixture(groupManager = groupManager)
      val entity = HttpEntity("{}").withContentType(ContentTypes.`application/json`)

      Post(Uri./.withPath(Path("/")), entity) ~> f.groupsController.route ~> check {
        rejection shouldBe a[Rejections.ConflictingChange]
        inside(rejection) {
          case Rejections.ConflictingChange(error) =>
            error.message should be("Group / is already created. Use PUT to change this group.")
        }
      }
    }

    "fail with rejection for group name that is already an app name" in {
      val groupManager = mock[GroupManager]
      val rootGroup = createRootGroup(apps = Map(
        "/appname".toRootPath -> AppDefinition("/appname".toRootPath, cmd = Some("cmd"), networks = Seq(ContainerNetwork("foo")))
      ))
      groupManager.rootGroup() returns rootGroup
      val f = new Fixture(groupManager = groupManager)
      val entity = HttpEntity("{}").withContentType(ContentTypes.`application/json`)

      Post(Uri./.withPath(Path("/appname")), entity) ~> f.groupsController.route ~> check {
        rejection shouldBe a[Rejections.ConflictingChange]
        inside(rejection) {
          case Rejections.ConflictingChange(error) =>
            error.message should be("An app with the path /appname already exists.")
        }
      }
    }

    "create a group" in {
      val groupApiService = mock[GroupApiService]
      groupApiService.updateGroup(any, any, any, any)(any) returns Future.successful(createRootGroup())
      val groupManager = mock[GroupManager]
      groupManager.rootGroup() returns createRootGroup()
      groupManager.updateRootAsync(eq(PathId.empty), any, any, eq(false), any).returns(Future.successful(DeploymentPlan.empty.copy(id = "plan", version = Timestamp.zero)))

      val f = new Fixture(groupManager = groupManager, groupApiService = groupApiService)
      val entity = HttpEntity("{}").withContentType(ContentTypes.`application/json`)

      Post(Uri./.withPath(Path("/newgroup")), entity) ~> f.groupsController.route ~> check {
        responseAs[String] should be ("""{
                                        |  "deploymentId" : "plan",
                                        |  "version" : "1970-01-01T00:00:00Z"
                                        |}""".stripMargin)
      }
    }
  }

  "Delete Group" should {

    "delete group" in new Fixture {
      groupManager.updateRootEither(org.mockito.Matchers.eq(PathId("/groupname").parent), any, any, org.mockito.Matchers.eq(false), any) returns Future.successful(Right(DeploymentPlan.empty.copy(id = "plan", version = Timestamp.zero)))

      Delete(Uri./.withPath(Path("/groupname"))) ~> groupsController.route ~> check {
        header[Headers.`Marathon-Deployment-Id`] should be(Some(Headers.`Marathon-Deployment-Id`("plan")))
        responseAs[String] should be ("""{
                                        |  "deploymentId" : "plan",
                                        |  "version" : "1970-01-01T00:00:00Z"
                                        |}""".stripMargin)
      }
    }

    // Unauthenticated access test cases
    {
      val controller = Fixture(authenticated = false).groupsController
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Delete(Uri./.withPath(Path("/groupname"))))
    }
  }

  "extracts embeds into group and app" in new Fixture {
    import akka.http.scaladsl.server.Directives._

    Get(Uri./.withPath(Path("/groupname")).withQuery(Query("embed" -> "group.apps.lastTaskFailure", "embed" -> "group.groups"))) ~> {
      groupsController.extractEmbeds {
        case (appEmbed: Set[AppInfo.Embed], groupEmbed: Set[GroupInfo.Embed]) => complete(s"App: ${appEmbed.mkString(",")}, Group: ${groupEmbed.mkString(",")}")
      }
    } ~> check { responseAs[String] should be ("App: LastTaskFailure, Group: Groups") }
  }

  "uses default embeds if no specified" in new Fixture {
    import akka.http.scaladsl.server.Directives._

    Get(Uri./.withPath(Path("/groupname"))) ~> {
      groupsController.extractEmbeds {
        case (appEmbed: Set[AppInfo.Embed], groupEmbed: Set[GroupInfo.Embed]) => complete(s"App: ${appEmbed.mkString(",")}, Group: ${groupEmbed.mkString(",")}")
      }
    } ~> check { responseAs[String] should be ("App: , Group: Apps,Pods,Groups") }
  }

  case class Fixture(
      authenticated: Boolean = true,
      authorized: Boolean = true,
      authFn: Any => Boolean = _ => true,
      infoService: GroupInfoService = mock[GroupInfoService],
      groupManager: GroupManager = mock[GroupManager],
      groupApiService: GroupApiService = mock[GroupApiService]) {
    val config: AllConf = AllConf.withTestConfig()

    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    implicit val authenticator = authFixture.auth

    val groupsController: GroupsController = new GroupsController(electionService, infoService, groupManager, groupApiService, config)
  }

  case class FixtureWithRealGroupManager(
      authenticated: Boolean = true,
      authorized: Boolean = true,
      authFn: Any => Boolean = _ => true,
      initialRoot: RootGroup = RootGroup.empty,
      infoService: GroupInfoService = mock[GroupInfoService]) {
    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    val f = new TestGroupManagerFixture(initialRoot)
    val config: AllConf = f.config
    val groupManager: GroupManager = f.groupManager
    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    implicit val authenticator = authFixture.auth

    val groupsController: GroupsController = new GroupsController(electionService, infoService, groupManager, new GroupApiService(groupManager), config)
  }
}
