package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import org.scalatest.{ GivenWhenThen, Matchers }

class AppVersionsResourceTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response

    When(s"the index is fetched")
    val index = appsVersionsResource.index("appId", req, resp)
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one app version is fetched")
    val show = appsVersionsResource.show("appId", "version", req, resp)
    Then("we receive a NotAuthenticated response")
    show.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthenticated request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response

    When(s"the index is fetched")
    val index = appsVersionsResource.index("appId", req, resp)
    Then("we receive a not authorized response")
    index.getStatus should be(auth.UnauthorizedStatus)

    When(s"one app version is fetched")
    val show = appsVersionsResource.show("appId", "version", req, resp)
    Then("we receive a not authorized response")
    show.getStatus should be(auth.UnauthorizedStatus)
  }

  var service: MarathonSchedulerService = _
  var config: MarathonConf = _
  var appsVersionsResource: AppVersionsResource = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    config = mock[MarathonConf]
    service = mock[MarathonSchedulerService]
    appsVersionsResource = new AppVersionsResource(service, auth.auth, auth.auth, config)
  }
}
