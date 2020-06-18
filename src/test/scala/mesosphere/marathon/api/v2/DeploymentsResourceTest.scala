package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.{JsonTestHelper, TestAuthFixture}
import mesosphere.marathon.core.deployment.{DeploymentPlan, DeploymentStep, DeploymentStepInfo}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import mesosphere.marathon.test.{GroupCreation, JerseyTest}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeploymentsResourceTest extends UnitTest with GroupCreation with JerseyTest {

  case class Fixture(
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      groupManager: GroupManager = mock[GroupManager],
      config: MarathonConf = mock[MarathonConf],
      auth: TestAuthFixture = new TestAuthFixture
  ) {
    val deploymentsResource = new DeploymentsResource(service, groupManager, auth.auth, auth.auth, config)
  }

  "Deployments Resource" should {
    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val app = AppDefinition(AbsolutePathId("/test"), cmd = Some("sleep"), role = "*")
      val targetGroup = createRootGroup(apps = Map(app.id -> app))
      val deployment = DeploymentStepInfo(DeploymentPlan(createRootGroup(), targetGroup), DeploymentStep(Seq.empty), 1)
      service.listRunningDeployments() returns Future.successful(Seq(deployment))

      When("the i r =>ndex is fetched")
      val running = asyncRequest { r => deploymentsResource.running(req, r) }
      Then("we receive a NotAuthenticated response")
      running.getStatus should be(auth.NotAuthenticatedStatus)

      When("one app version is fetched")
      val cancel = asyncRequest { r => deploymentsResource.cancel(deployment.plan.id, false, req, r) }
      Then("we receive a NotAuthenticated response")
      cancel.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val app = AppDefinition(AbsolutePathId("/test"), cmd = Some("sleep"), role = "*")
      val targetGroup = createRootGroup(apps = Map(app.id -> app))
      val deployment = DeploymentStepInfo(DeploymentPlan(createRootGroup(), targetGroup), DeploymentStep(Seq.empty), 1)
      service.listRunningDeployments() returns Future.successful(Seq(deployment))

      When("one app version is fetched")
      val cancel = asyncRequest { r => deploymentsResource.cancel(deployment.plan.id, false, req, r) }
      Then("we receive a not authorized response")
      cancel.getStatus should be(auth.UnauthorizedStatus)
    }

    "get a simple deployment" in new Fixture {
      Given("a simple request")
      auth.authenticated = true
      auth.authorized = true

      val req = auth.request
      val app = AppDefinition(AbsolutePathId("/test"), cmd = Some("sleep"), role = "*")
      val targetGroup = createRootGroup(apps = Map(app.id -> app))
      val deployment = DeploymentStepInfo(
        DeploymentPlan(
          original = createRootGroup(),
          target = targetGroup,
          id = Some("af5afbe7-bf2c-490c-af8a-00d36bcb0b07"),
          version = Timestamp("2019-10-14T13:00:52.928Z")
        ),
        step = DeploymentStep(Seq.empty),
        stepIndex = 1
      )
      service.listRunningDeployments() returns Future.successful(Seq(deployment))

      When("running deployments are fetched")
      val running = asyncRequest { r => deploymentsResource.running(req, r) }

      val responseString = running.getEntity.toString

      val expected =
        """
          |[
          |  {
          |    "id": "af5afbe7-bf2c-490c-af8a-00d36bcb0b07",
          |    "version": "2019-10-14T13:00:52.928Z",
          |    "affectedApps": [
          |      "/test"
          |    ],
          |    "affectedPods": [],
          |    "steps": [
          |      {
          |        "actions": [
          |          {
          |            "action": "StartApplication",
          |            "app": "/test"
          |          }
          |        ]
          |      },
          |      {
          |        "actions": [
          |          {
          |            "action": "ScaleApplication",
          |            "app": "/test"
          |          }
          |        ]
          |      }
          |    ],
          |    "currentActions": [],
          |    "currentStep": 1,
          |    "totalSteps": 2
          |  }
          |]
        """.stripMargin

      JsonTestHelper.assertThatJsonString(responseString).correspondsToJsonString(expected)
    }
  }

}
