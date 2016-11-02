package mesosphere.marathon
package integration

import java.io.File
import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId
import org.apache.commons.io.FileUtils

import scala.collection.immutable

/**
  * Tests that ensure marathon continues working after restarting (for example, as a result of a leader abdication
  * while deploying)
  */
@IntegrationTest
class RestartIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with MarathonFixture {
  import PathId._
  val abdicationLoops = 2

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

      (1 to abdicationLoops).foreach { _ =>
        And("the leader abdicates")
        server.restart()
        val tasksAfterFirstAbdication = f.waitForTasks(app.id.toPath, 1)
        Then("the already running task should not be killed")
        tasksBeforeAbdication should be(tasksAfterFirstAbdication)
      }
    }

    "readiness" should {
      "deployment with 1 ready and 1 not ready instance is continued properly after a restart" in withMarathon("readiness") { (server, f) =>
        val readinessCheck = raml.ReadinessCheck(
          "ready",
          portName = "http",
          path = "/v1/plan",
          intervalSeconds = 2,
          timeoutSeconds = 1,
          preserveLastResponse = true)

        val appId = f.testBasePath / "app"
        val create = f.appProxy(appId, versionId = "v1", instances = 2, healthCheck = None)

        val plan = "phase(block1)"
        val update = raml.AppUpdate(
          cmd = Some(s"""${serviceMockScript(f)} '$plan'"""),
          portDefinitions = Some(immutable.Seq(raml.PortDefinition(name = Some("http")))),
          readinessChecks = Some(Seq(readinessCheck)))
        testDeployments(server, f, appId, create, update)
      }
    }
    "health checks" should {
      "deployment with 1 healthy and 1 unhealthy instance is continued properly after master abdication" in withMarathon("health-check") { (server, f) =>
        val healthCheck: raml.AppHealthCheck = raml.AppHealthCheck(
          path = Some("/v1/plan"),
          portIndex = Some(0),
          intervalSeconds = 2,
          timeoutSeconds = 1,
          protocol = raml.AppHealthCheckProtocol.Http)
        val appId = f.testBasePath / "app"
        val create = f.appProxy(appId, versionId = "v1", instances = 2, healthCheck = None)

        val plan = "phase(block1)"
        val update = raml.AppUpdate(
          cmd = Some(s"""${serviceMockScript(f)} '$plan'"""),
          portDefinitions = Some(immutable.Seq(raml.PortDefinition(name = Some("http")))),
          healthChecks = Some(Set(healthCheck)))

        testDeployments(server, f, appId, create, update)
      }
    }
  }

  private def testDeployments(server: LocalMarathon, f: MarathonTest, appId: PathId, createApp: raml.App, updateApp: raml.AppUpdate): Unit = {
    Given("a new simple app with 2 instances")
    createApp.instances shouldBe 2

    val created = f.marathon.createAppV2(createApp)
    created.code should be (201)
    f.waitForDeployment(created)

    logger.debug(s"Started app: ${f.marathon.app(appId).entityPrettyJsonString}")

    When("updating the app")
    val appV2 = f.marathon.updateApp(appId, updateApp)

    And("new tasks are started and running")
    val updated = f.waitForTasks(appId, 4) //make sure there are 2 additional tasks

    val newVersion = appV2.value.version.toString
    val updatedTasks = updated.filter(_.version.contains(newVersion))
    val updatedTaskIds: List[String] = updatedTasks.map(_.id)
    updatedTaskIds should have size 2

    logger.debug(s"Updated app: ${f.marathon.app(appId).entityPrettyJsonString}")

    And("ServiceMock1 is up")
    val serviceFacade1 = ServiceMockFacade(f.marathon.tasks(appId).value) { task =>
      task.version.contains(newVersion) && task.launched
    }
    And("We trigger the first new task to continue service migration")
    serviceFacade1.continue()

    When("we force the leader to abdicate to simulate a failover")
    server.restart().futureValue

    And("second updated task becomes healthy")
    val serviceFacade2 = ServiceMockFacade(f.marathon.tasks(appId).value) { task =>
      task != serviceFacade1.task && task.version.contains(newVersion) && task.launched
    }
    And("We trigger the second new task to continue service migration")
    serviceFacade2.continue()

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

  /**
    * Create a shell script that can start a service mock
    */
  def serviceMockScript(f: MarathonTest): String = {
    val uuid = UUID.randomUUID.toString
    f.appProxyIds(_ += uuid)
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[ServiceMock].getName
    val run = s"""$javaExecutable -DappProxyId=$uuid -DtestSuite=$suiteName -Xmx64m -classpath $classPath $main"""
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
