package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.{AppHealthCheck, AppHealthCheckProtocol}
import mesosphere.marathon.state.AbsolutePathId

import scala.concurrent.duration._

class HealthCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  def appId(suffix: Option[String] = None): AbsolutePathId = testBasePath / s"app-${suffix.getOrElse(UUID.randomUUID)}"

  "Health checks" should {
    "kill instance with failing Marathon health checks" in {
      Given("a deployed app with health checks")
      val id = appId(Some(s"replace-marathon-http-health-check"))
      val app = appProxy(id, "v1", instances = 1, healthCheck = None).copy(healthChecks = Set(ramlHealthCheck(AppHealthCheckProtocol.Http)))
      val check = registerAppProxyHealthCheck(id, "v1", state = true)
      val result = marathon.createAppV2(app)
      result should be(Created)
      waitForDeployment(result)

      When("the app becomes unhealthy")
      val oldTaskId = marathon.tasks(id).value.head.id
      check.afterDelay(1.seconds, false)

      Then("the unhealthy instance is killed")
      waitForEventWith(
        "unhealthy_instance_kill_event",
        { event => event.info("taskId") == oldTaskId },
        "Unhealthy instance killed event was not sent."
      )

      And("a replacement is started")
      check.afterDelay(1.seconds, true)
      eventually {
        val currentTasks = marathon.tasks(id).value
        currentTasks should have size (1)
        currentTasks.map(_.id) should not contain (oldTaskId)
      }
    }

    "kill instance with failing Mesos health checks" in {
      Given("a deployed app with health checks")
      val id = appId(Some(s"replace-mesos-http-health-check"))
      val app =
        appProxy(id, "v1", instances = 1, healthCheck = None).copy(healthChecks = Set(ramlHealthCheck(AppHealthCheckProtocol.MesosHttp)))
      val check = registerAppProxyHealthCheck(id, "v1", state = true)
      val result = marathon.createAppV2(app)
      result should be(Created)
      waitForDeployment(result)

      When("the app becomes unhealthy")
      val oldTaskId = marathon.tasks(id).value.head.id
      val oldInstanceId = Task.Id.parse(oldTaskId).instanceId.idString
      check.afterDelay(1.seconds, false)

      Then("the unhealthy instance is killed")
      waitForEventWith(
        "instance_changed_event",
        { event => event.info("condition") == "Killed" && event.info("instanceId") == oldInstanceId },
        "Unhealthy instance killed event was not sent."
      )

      And("a replacement is started")
      check.afterDelay(1.seconds, true)
      eventually {
        val currentTasks = marathon.tasks(id).value
        currentTasks should have size (1)
        currentTasks.map(_.id) should not contain (oldTaskId)
      }
    }
  }

  private def ramlHealthCheck(protocol: AppHealthCheckProtocol) =
    AppHealthCheck(
      path = Some("/health"),
      protocol = protocol,
      gracePeriodSeconds = 3,
      intervalSeconds = 1,
      maxConsecutiveFailures = 3,
      portIndex = Some(0),
      delaySeconds = 3
    )
}
