package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{ Path, Query }
import akka.http.scaladsl.server.MalformedQueryParamRejection
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation

class AppsDirectivesTest extends UnitTest with ScalatestRouteTest {
  import AppsDirectives._

  def objectName(obj: AnyRef) = obj.getClass.getName

  val route = extractTaskKillingMode { mode =>
    complete(objectName(mode))
  }

  "AppsDirectives" should {
    "extract scale killing mode from request" in {
      Get().withUri(Uri./.withQuery(Query("scale" -> "true"))) ~> route ~> check {
        responseAs[String] shouldEqual objectName(TaskKillingMode.Scale)
      }
    }
    "extract wipe killing mode from request" in {
      Get().withUri(Uri./.withQuery(Query("wipe" -> "true"))) ~> route ~> check {
        responseAs[String] shouldEqual objectName(TaskKillingMode.Wipe)
      }
    }
    "extract killWithoutWipe killing mode from request" in {
      Get().withUri(Uri./) ~> route ~> check {
        responseAs[String] shouldEqual objectName(TaskKillingMode.KillWithoutWipe)
      }
    }
    "reject when scale and wipe are both true" in {
      Get().withUri(Uri./.withQuery(Query("wipe" -> "true", "scale" -> "true"))) ~> route ~> check {
        rejection shouldBe a[MalformedQueryParamRejection]
      }
    }
  }
}
