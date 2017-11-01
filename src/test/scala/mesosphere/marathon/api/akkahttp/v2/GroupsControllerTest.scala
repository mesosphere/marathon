package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.Uri.{ Path, Query }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.akkahttp.Rejections.EntityNotFound
import mesosphere.marathon.core.appinfo.{ AppInfo, GroupInfo, GroupInfoService }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.{ GroupCreation, SettableClock }
import org.scalatest.Inside

import scala.concurrent.Future

class GroupsControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging with GroupCreation {

  "Group detail" should {
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
        responseAs[String] should include (""""id" : "/test/group"""")
      }
    }

    "returns empty group if root and no group found" in {
      val infoService = mock[GroupInfoService]
      infoService.selectGroup(any, any, any, any) returns Future.successful(None)
      val f = new Fixture(infoService = infoService)

      Get(Uri./.withPath(Path("/"))) ~> f.groupsController.route ~> check {
        status should be(StatusCodes.OK)
        responseAs[String] should include (""""id" : "/"""")
      }
    }

    "rejects with group not found for nonexisting group" in {
      val infoService = mock[GroupInfoService]
      infoService.selectGroup(any, any, any, any) returns Future.successful(None)
      val f = new Fixture(infoService = infoService)

      Get(Uri./.withPath(Path("/groupname"))) ~> f.groupsController.route ~> check {
        rejection should be (EntityNotFound.noGroup("groupname".toRootPath))
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
        responseAs[String] should be ("[ \"2015-04-09T12:30:00.000Z\", \"2015-04-09T12:30:00.000Z\" ]")
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
        responseAs[String] should be ("[ \"2015-04-09T12:30:00.000Z\" ]")
      }
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
      groupManager: GroupManager = mock[GroupManager]) {
    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    implicit val authenticator = authFixture.auth

    val groupsController: GroupsController = new GroupsController(electionService, infoService, groupManager)
  }
}
