package mesosphere.marathon
package integration

import java.io.File

import mesosphere.AkkaIntegrationFunTest
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.{ PortDefinition, UpgradeStrategy }
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.Try

@IntegrationTest
class TwoAppsDeployWithAbdicationIntegrationTest extends AkkaIntegrationFunTest with MarathonClusterTest {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //            ,--.!,
  //       __/   -*-
  //     ,d08b.  '|`
  //     0088MM
  //     `9MMP'
  test("deployment is restarted properly on master abdication") {
    Given("a new app with 2 instances and no health checks")
    val appId = testBasePath / "app"

    val appv1 = appProxy(appId, "v1", instances = 2, healthCheck = None)
    marathon.createAppV2(appv1).code should be (201)
    waitForEvent("deployment_success")

    val started = marathon.tasks(appId)
    val startedTaskIds = started.value.map(_.id)
    log.info(s"Started app: ${marathon.app(appId).entityPrettyJsonString}")

    When("updated with health check and minimumHealthCapacity=1")

    // ServiceMock will block the deployment (returning HTTP 503) until called continue()
    val plan = "phase(block1)"
    val appv2 = marathon.updateApp(appId, AppUpdate(
      cmd = Some(s"""$serviceMockScript '$plan'"""),
      portDefinitions = Some(immutable.Seq(PortDefinition(0, name = Some("http")))),
      healthChecks = Some(Set(healthCheck)),
      upgradeStrategy = Some(UpgradeStrategy(minimumHealthCapacity = 1.0))))

    And("new and updated tasks are started successfully")
    val updated = waitForTasks(appId, 4, maxWait = 90.seconds) //make sure, the new task has really started

    val updatedTasks = updated.diff(started.value)
    val updatedTaskIds: List[String] = updated.map(_.id).diff(startedTaskIds)

    log.info(s"Updated app: ${marathon.app(appId).entityPrettyJsonString}")

    And("first updated task becomes healthy")
    val serviceFacade1 = new ServiceMockFacade(updatedTasks.head)
    WaitTestSupport.waitUntil("ServiceMock1 is up", 30.seconds){ Try(serviceFacade1.plan()).isSuccess }
    // This would move the service mock from "InProgress" [HTTP 503] to "Complete" [HTTP 200]
    serviceFacade1.continue()
    waitForEvent("health_status_changed_event")

    When("marathon leader is abdicated")
    val leader = marathon.leader().value
    marathon.abdicate().code should be (200)

    And("a new leader is elected")
    WaitTestSupport.waitUntil("the leader changes", 30.seconds) { marathon.leader().value != leader }

    And("second updated task becomes healthy")
    val serviceFacade2 = new ServiceMockFacade(updatedTasks.last)
    WaitTestSupport.waitUntil("ServiceMock is up", 30.seconds){ Try(serviceFacade2.plan()).isSuccess }
    // This would move the service mock from "InProgress" [HTTP 503] to "Complete" [HTTP 200]
    serviceFacade2.continue()
    waitForEvent("health_status_changed_event")

    Then("the app should have only 2 tasks launched")
    waitForTasks(appId, 2) should have size 2

    And("app was deployed successfully")
    waitForEventMatching("app should be restarted and deployment should be finished") { matchDeploymentSuccess(2, appId.toString) }

    val after = marathon.tasks(appId)
    val afterTaskIds = after.value.map(_.id)
    log.info(s"App after restart: ${marathon.app(appId).entityPrettyJsonString}")

    And("taskIds after restart should be equal to the updated taskIds (not started ones)")
    afterTaskIds should equal (updatedTaskIds)
  }

  private def matchDeploymentSuccess(instanceCount: Int, appId: String): CallbackEvent => Boolean = { event =>
    val infoString = event.info.toString()
    event.eventType == "deployment_success" && infoString.contains(s"instances -> $instanceCount") && matchRestartApplication(infoString, appId)
  }

  private def matchRestartApplication(infoString: String, appId: String): Boolean = {
    infoString.contains(s"List(Map(actions -> List(Map(action -> RestartApplication, app -> $appId)))))")
  }

  private lazy val healthCheck: MarathonHttpHealthCheck = MarathonHttpHealthCheck(
    path = Some("/v1/plan"),
    portIndex = Some(PortReference(0)),
    interval = 2.seconds,
    timeout = 1.second)

  /**
    * Create a shell script that can start a service mock
    */
  private lazy val serviceMockScript: String = {
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[ServiceMock].getName
    val run = s"""$javaExecutable -Xmx64m -classpath $classPath $main"""
    val file = File.createTempFile("serviceProxy", ".sh")
    file.deleteOnExit()

    FileUtils.write(
      file,
      s"""#!/bin/sh
          |set -x
          |exec $run $$*""".stripMargin)
    file.setExecutable(true)
    file.getAbsolutePath
  }
}
