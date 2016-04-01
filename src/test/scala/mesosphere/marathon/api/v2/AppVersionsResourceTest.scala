package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.state.{ GroupManager, Timestamp, AppDefinition }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.Future

class AppVersionsResourceTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request

    When(s"the index is fetched")
    val index = appsVersionsResource.index("appId", req)
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one app version is fetched")
    val show = appsVersionsResource.show("appId", "version", req)
    Then("we receive a NotAuthenticated response")
    show.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access to index without authorization is denied when the app exists") {
    Given("An unauthenticated request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    groupManager.app("appId".toRootPath) returns Future.successful(Some(AppDefinition("appId".toRootPath)))
    When(s"the index is fetched")
    val index = appsVersionsResource.index("appId", req)
    Then("we receive a not authorized response")
    index.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to index without authorization leads to 404 when the app does not exist") {
    Given("An unauthenticated request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    groupManager.app("appId".toRootPath) returns Future.successful(None)
    When(s"the index is fetched")
    val index = appsVersionsResource.index("appId", req)
    Then("we receive a 404")
    index.getStatus should be(404)
  }

  test("access to show without authorization is denied when the app exists") {
    Given("An unauthenticated request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    val version = Timestamp.now()
    service.getApp("appId".toRootPath, version) returns Some(AppDefinition("appId".toRootPath))
    When(s"one app version is fetched")
    val show = appsVersionsResource.show("appId", version.toString, req)
    Then("we receive a not authorized response")
    show.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to show without authorization leads to a 404 when the app version does not exist") {
    Given("An unauthenticated request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    val version = Timestamp.now()
    service.getApp("appId".toRootPath, version) returns None
    When(s"one app version is fetched")
    val show = appsVersionsResource.show("appId", version.toString, req)
    Then("we receive a not authorized response")
    show.getStatus should be(404)
  }

  var service: MarathonSchedulerService = _
  var groupManager: GroupManager = _
  var config: MarathonConf = _
  var appsVersionsResource: AppVersionsResource = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    config = mock[MarathonConf]
    service = mock[MarathonSchedulerService]
    groupManager = mock[GroupManager]
    appsVersionsResource = new AppVersionsResource(service, groupManager, auth.auth, auth.auth, config)
  }
}
