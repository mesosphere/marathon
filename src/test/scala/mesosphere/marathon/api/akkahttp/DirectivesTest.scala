package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.{ HttpEntity, StatusCodes, Uri }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation

class DirectivesTest extends UnitTest with GroupCreation with ScalatestRouteTest {
  import Directives._
  import PathId.StringPathId

  trait DirectivesTestFixture {
    val app1 = AppDefinition("/test/group1/app1".toPath)
    val app2 = AppDefinition("/test/group2/app2".toPath)
    val rootGroup = createRootGroup(
      groups = Set(
        createGroup("/test".toPath, groups = Set(
          createGroup("/test/group1".toPath, Map(app1.id -> app1)),
          createGroup("/test/group2".toPath, Map(app2.id -> app2))
        ))))

  }

  "ExistingAppPathId matcher" should {

    "not match groups" in new DirectivesTestFixture {
      ExistingAppPathId(rootGroup)(Path("test/group1")) shouldBe Unmatched
    }

    "match apps that exist" in new DirectivesTestFixture {
      ExistingAppPathId(rootGroup)(Path("test/group1/app1")) shouldBe Matched(Path(""), Tuple1("/test/group1/app1".toPath))
    }

    "match not match apps that don't exist" in new DirectivesTestFixture {
      ExistingAppPathId(rootGroup)(Path("test/group1/app3")) shouldBe Unmatched
    }

    "leave path components after matching appIds unconsumed" in new DirectivesTestFixture {
      ExistingAppPathId(rootGroup)(Path("test/group1/app1/restart/ponies")) shouldBe Matched(Path("/restart/ponies"), Tuple1("/test/group1/app1".toPath))
    }
  }

  "extractExistingAppId Directive" should {
    trait ExtractExistingAppFixture extends DirectivesTestFixture {
      val rawRoute = extractExistingAppId(rootGroup) { appId =>
        get {
          complete(appId.toString)
        }
      }
      import akka.http.scaladsl.server._
      import EntityMarshallers._

      implicit val rejectionHandler = RejectionHandler
        .newBuilder
        .handle {
          case Rejections.EntityNotFound(message) => complete(NotFound -> message)
        }
        .result()
      val route = Route.seal(rawRoute)

      def checkRejection(appName: String, expectedRejectionAppName: String = "") = {
        val appNameInRejection = if (expectedRejectionAppName.isEmpty) appName else expectedRejectionAppName
        Get(Uri./.withPath(Path./ ++ Path(appName)), HttpEntity.Empty) ~> route ~> check {
          status shouldEqual StatusCodes.NotFound
          responseAs[String] should include(s"App '/$appNameInRejection' does not exist")
        }
      }

      def checkExtraction(path: String) = {
        Get(Uri./.withPath(Path./ ++ Path(path)), HttpEntity.Empty) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual s"/$path"
        }
      }
    }

    "reject groups" in new ExtractExistingAppFixture {
      checkRejection("test/group1")
    }

    "extract apps that exist" in new ExtractExistingAppFixture {
      checkExtraction("test/group1/app1")
    }

    "reject apps that don't exist" in new ExtractExistingAppFixture {
      checkRejection("test/group1/app3")
    }

    "reject apps that don't exists and trim api-related keywords" in new ExtractExistingAppFixture {
      checkRejection("test/group1/app3/restart", "test/group1/app3")
      checkRejection("test/group1/app3/tasks", "test/group1/app3")
      checkRejection("test/group1/app3/versions", "test/group1/app3")
    }

  }
}
