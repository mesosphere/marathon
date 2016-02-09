package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.state.{ PathId, AppDefinition, Group, GroupManager }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.{ DeploymentStep, DeploymentPlan }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import org.scalatest.{ Matchers, GivenWhenThen }

import scala.concurrent.Future
import scala.collection.immutable.Seq

class DeploymentsResourceTest extends MarathonSpec with GivenWhenThen with Matchers with Mockito {

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val targetGroup = Group.empty.copy(apps = Set(AppDefinition(PathId("/test"))))
    val deployment = DeploymentStepInfo(DeploymentPlan(Group.empty, targetGroup), DeploymentStep(Seq.empty), 1)
    service.listRunningDeployments() returns Future.successful(Seq(deployment))

    When(s"the index is fetched")
    val running = deploymentsResource.running(req)
    Then("we receive a NotAuthenticated response")
    running.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one app version is fetched")
    val cancel = deploymentsResource.cancel(deployment.plan.id, false, req)
    Then("we receive a NotAuthenticated response")
    cancel.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val targetGroup = Group.empty.copy(apps = Set(AppDefinition(PathId("/test"))))
    val deployment = DeploymentStepInfo(DeploymentPlan(Group.empty, targetGroup), DeploymentStep(Seq.empty), 1)
    service.listRunningDeployments() returns Future.successful(Seq(deployment))

    When(s"one app version is fetched")
    val cancel = deploymentsResource.cancel(deployment.plan.id, false, req)
    Then("we receive a not authorized response")
    cancel.getStatus should be(auth.UnauthorizedStatus)
  }

  var service: MarathonSchedulerService = _
  var groupManager: GroupManager = _
  var config: MarathonConf = _
  var deploymentsResource: DeploymentsResource = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    groupManager = mock[GroupManager]
    config = mock[MarathonConf]
    service = mock[MarathonSchedulerService]
    deploymentsResource = new DeploymentsResource(service, groupManager, auth.auth, auth.auth, config)
  }
}
