package mesosphere.marathon
package integration

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually

import scala.collection.immutable

/**
  * Tests that ensure marathon continues working after restarting (for example, as a result of a leader abdication
  * while deploying)
  */
@IntegrationTest
class RestartIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with MarathonFixture with Inspectors with Eventually {
  import PathId._

  "Restarting Marathon" when {
    /**
      * Regression Test for https://github.com/mesosphere/marathon/issues/3783
      *
      * Intention:
      * During an abdication, there should be a deployment in progress, containing at
      * least one running task. This running task should not be killed/replaced during
      * the next leader takes over the deployment.
      *
      * Adapted from https://github.com/EvanKrall/reproduce_marathon_issue_3783
      */
    "not kill a running task currently involved in a deployment" in withMarathon("restart-dont-kill") { (server, f) =>
      Given("a new app with an impossible constraint")
      // Running locally, the constraint of a unique hostname should prevent the second instance from deploying.
      val constraint = raml.Constraints("hostname" -> "UNIQUE")
      val app = f.appProxy(PathId("/restart-dont-kill"), "v2", instances = 2, healthCheck = None)
        .copy(constraints = constraint)
      f.marathon.createAppV2(app)

      When("one of the tasks is deployed")
      val tasksBeforeAbdication = f.waitForTasks(app.id.toPath, 1)

      And("the leader abdicates")
      server.restart().futureValue
      val tasksAfterFirstAbdication = f.waitForTasks(app.id.toPath, 1)
      Then("the already running task should not be killed")
      tasksBeforeAbdication should be(tasksAfterFirstAbdication) withClue (s"Tasks before (${tasksBeforeAbdication}) and after (${tasksAfterFirstAbdication}) abdication are different")
    }

    "readiness" should {
      "deployment with 1 ready and 1 not ready instance is continued properly after a restart" in withMarathon("readiness") { (server, f) =>
        val ramlReadinessCheck = raml.ReadinessCheck(
          name = "ready",
          portName = "http",
          path = "/ready",
          intervalSeconds = 2,
          timeoutSeconds = 1,
          preserveLastResponse = true
        )

        Given("a new simple app with 2 instances")
        val appId = nextAppId(f)
        val createApp = f.appProxy(appId, versionId = "v1", instances = 2, healthCheck = None)

        createApp.instances shouldBe 2 withClue (s"${appId} has ${createApp.instances} instances running but there should be 2.")

        val created = f.marathon.createAppV2(createApp)
        created should be(Created)
        f.waitForDeployment(created)

        When("updating the app")
        val updateApp = raml.AppUpdate(
          cmd = f.appProxy(appId, versionId = "v2", instances = 2).cmd,
          portDefinitions = Some(immutable.Seq(raml.PortDefinition(name = Some("http")))),
          readinessChecks = Some(Seq(ramlReadinessCheck)))
        val appV2 = f.marathon.updateApp(appId, updateApp)

        Then("new tasks are launched")
        //make sure there are 2 additional tasks
        val updated = f.waitForTasks(appId, 4) withClue (s"The new tasks for ${appId} did not start running.")

        val newVersion = appV2.value.version.toString
        val updatedTasks = updated.filter(_.version.contains(newVersion))
        val updatedTaskIds: List[String] = updatedTasks.map(_.id)
        updatedTaskIds should have size 2 withClue (s"${appId} has ${updatedTaskIds.size} updated tasks but there should be 2.")

        And("new tasks are running")
        eventually {
          forAll (updatedTasks) { _.state should be("TASK_RUNNING") }
        }

        Given("The first task is ready")
        val firstTaskReadinessCheck :: otherTaskReadinessChecks =
          updatedTaskIds.map(taskId => f.registerProxyReadinessCheck(appId, "v2", Some(taskId)))
        firstTaskReadinessCheck.isReady.set(true)

        And("we force the leader to abdicate to simulate a failover")
        server.restart().futureValue
        f.waitForSSEConnect()

        Then("There is still one ongoing deployment")
        eventually {
          val deployments = f.marathon.listDeploymentsForBaseGroup().value
          deployments should have size 1 withClue (s"Expected 1 ongoing deployment but found ${deployments.size}")
        }

        When("second updated task becomes healthy")
        otherTaskReadinessChecks.foreach(_.isReady.set(true))

        Then("the app should eventually have only 2 tasks launched")
        f.waitForTasks(appId, 2) should have size 2

        And("app was deployed successfully")
        f.waitForDeployment(appV2)

        val after = f.marathon.tasks(appId)
        val afterTaskIds = after.value.map(_.id)

        And("taskIds after restart should be equal to the updated taskIds (not started ones)")
        afterTaskIds.sorted should equal (updatedTaskIds.sorted) withClue (s"App after restart: ${f.marathon.app(appId).entityPrettyJsonString}")

      }
    }
    "health checks" should {
      "deployment with 2 unhealthy instances is continued properly after master abdication" in withMarathon("health-check") { (server, f) =>
        val ramlHealthCheck: raml.AppHealthCheck = raml.AppHealthCheck(
          path = Some("/health"),
          portIndex = Some(0),
          intervalSeconds = 2,
          timeoutSeconds = 1,
          protocol = raml.AppHealthCheckProtocol.Http)

        Given("a new simple app with 2 instances")
        val appId = nextAppId(f)
        val createApp = f.appProxy(appId, versionId = "v1", instances = 2, healthCheck = None)
        val created = f.marathon.createAppV2(createApp)
        created should be(Created)
        f.waitForDeployment(created)

        logger.debug(s"Started app: ${f.marathon.app(appId).entityPrettyJsonString}")

        And("an update with health checks enabled")
        val updateApp = raml.AppUpdate(
          cmd = f.appProxy(appId, versionId = "v2", instances = 2).cmd,
          portDefinitions = Some(immutable.Seq(raml.PortDefinition(name = Some("http")))),
          healthChecks = Some(Set(ramlHealthCheck)))

        And("both new task will be unhealthy")
        val healthCheck = f.registerAppProxyHealthCheck(appId, "v2", state = false)

        When("updating the app")
        val appV2 = f.marathon.updateApp(appId, updateApp)

        Then("new tasks are started")
        val updated = f.waitForTasks(appId, 4) withClue (s"The new tasks for ${appId} did not start running.") //make sure there are 2 additional tasks

        val newVersion = appV2.value.version.toString
        val updatedTasks = updated.filter(_.version.contains(newVersion))
        val updatedTaskIds: List[String] = updatedTasks.map(_.id)
        updatedTaskIds should have size 2 withClue (s"Update ${updatedTaskIds.size} instead of 2 for ${appId}")

        And("new tasks are running")
        eventually {
          forAll (updatedTasks) { _.state should be("TASK_RUNNING") }
        }

        logger.debug(s"Updated app: ${f.marathon.app(appId).entityPrettyJsonString}")

        When("we force the leader to abdicate to simulate a failover")
        server.restart().futureValue
        f.waitForSSEConnect()

        Then("there is still one ongoing deployment")
        eventually {
          val deployments = f.marathon.listDeploymentsForBaseGroup().value
          deployments should have size 1 withClue (s"Expected 1 ongoing deployment but found ${deployments.size}")
        }

        When("both updated task become healthy")
        healthCheck.state = true

        Then("the app should eventually have only 2 tasks launched")
        f.waitForTasks(appId, 2) should have size 2

        And("app was deployed successfully")
        f.waitForDeployment(appV2)

        val after = f.marathon.tasks(appId)
        val afterTaskIds = after.value.map(_.id)
        logger.debug(s"App after restart: ${f.marathon.app(appId).entityPrettyJsonString}")

        And("taskIds after restart should be equal to the updated taskIds (not started ones)")
        afterTaskIds.sorted should equal (updatedTaskIds.sorted)
      }
    }
  }

  val appIdCount = new AtomicInteger()
  def nextAppId(f: MarathonTest): PathId = f.testBasePath / s"app-${appIdCount.getAndIncrement()}"
}
