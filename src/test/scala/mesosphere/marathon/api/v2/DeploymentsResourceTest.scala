package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep, DeploymentStepInfo }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation

import scala.collection.immutable.Seq
import scala.concurrent.Future

class DeploymentsResourceTest extends UnitTest with GroupCreation {

  case class Fixture(
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      groupManager: GroupManager = mock[GroupManager],
      config: MarathonConf = mock[MarathonConf],
      auth: TestAuthFixture = new TestAuthFixture) {
    val deploymentsResource = new DeploymentsResource(service, groupManager, auth.auth, auth.auth, config)
  }

  "Deployments Resource" should {
    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val app = AppDefinition(PathId("/test"), cmd = Some("sleep"))
      val targetGroup = createRootGroup(apps = Map(app.id -> app))
      val deployment = DeploymentStepInfo(DeploymentPlan(createRootGroup(), targetGroup), DeploymentStep(Seq.empty), 1)
      service.listRunningDeployments() returns Future.successful(Seq(deployment))

      When("the index is fetched")
      val running = deploymentsResource.running(req)
      Then("we receive a NotAuthenticated response")
      running.getStatus should be(auth.NotAuthenticatedStatus)

      When("one app version is fetched")
      val cancel = deploymentsResource.cancel(deployment.plan.id, false, req)
      Then("we receive a NotAuthenticated response")
      cancel.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val app = AppDefinition(PathId("/test"), cmd = Some("sleep"))
      val targetGroup = createRootGroup(apps = Map(app.id -> app))
      val deployment = DeploymentStepInfo(DeploymentPlan(createRootGroup(), targetGroup), DeploymentStep(Seq.empty), 1)
      service.listRunningDeployments() returns Future.successful(Seq(deployment))

      When("one app version is fetched")
      val cancel = deploymentsResource.cancel(deployment.plan.id, false, req)
      Then("we receive a not authorized response")
      cancel.getStatus should be(auth.UnauthorizedStatus)
    }
  }

}
