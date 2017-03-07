package mesosphere.marathon
package integration

import java.io.File
import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.health.{ HealthCheck, MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state._
import org.apache.commons.io.FileUtils
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

@IntegrationTest
class ReadinessCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Eventually {

  //clean up state before running the test case
  after(cleanUp())

  "ReadinessChecks" should {
    "A deployment of an application with readiness checks (no health) does finish when the plan is ready" in {
      deploy(serviceProxy("/readynohealth".toTestPath, "phase(block1!,block2!,block3!)", withHealth = false), continue = true)
    }

    "A deployment of an application with readiness checks and health does finish when health checks succeed and plan is ready" in {
      deploy(serviceProxy("/readyhealth".toTestPath, "phase(block1!,block2!,block3!)", withHealth = true), continue = true)
    }

    "A deployment of an application without readiness checks and health does finish when health checks succeed" in {
      deploy(serviceProxy("/noreadyhealth".toTestPath, "phase()", withHealth = true), continue = false)
    }

    "A deployment of an application without readiness checks and without health does finish" in {
      deploy(serviceProxy("/noreadynohealth".toTestPath, "phase()", withHealth = false), continue = false)
    }

    "An upgrade of an application will wait for the readiness checks" in {
      val serviceDef = serviceProxy("/upgrade".toTestPath, "phase(block1!,block2!,block3!)", withHealth = false)
      deploy(serviceDef, continue = true)

      When("The service is upgraded")
      val oldTask = marathon.tasks(serviceDef.id).value.head
      val update = marathon.updateApp(serviceDef.id, AppUpdate(env = Some(EnvVarValue(sys.env))))

      And("The ServiceMock is up")
      val serviceFacade = createServiceFacade(serviceDef.id) { task =>
        task.id != oldTask.id && task.state == MesosProtos.TaskState.TASK_RUNNING.name()
      }

      Then("The deployment does not succeed until the readiness checks succeed")
      while (serviceFacade.plan().code != 200) {
        When("We continue on block until the plan is ready")
        serviceFacade.continue()
        marathon.listDeploymentsForBaseGroup().value should have size 1
      }
      waitForDeployment(update)
    }
  }

  def deploy(service: AppDefinition, continue: Boolean): Unit = {
    Given("An application service")
    val result = marathon.createAppV2(service)
    result.code should be (201)
    When("The ServiceMock is up")
    val serviceFacade = createServiceFacade(service.id) { task =>
      task.state == MesosProtos.TaskState.TASK_RUNNING.name()
    }

    while (continue && serviceFacade.plan().code != 200) {
      When("We continue on block until the plan is ready")
      marathon.listDeploymentsForBaseGroup().value should have size 1
      serviceFacade.continue()
    }

    Then("The deployment should finish")
    waitForDeployment(result)
  }

  def createServiceFacade(id: PathId)(predicate: (ITEnrichedTask) => Boolean): ServiceMockFacade = eventually {
    val newTask = marathon.tasks(id).value.find(predicate(_)).get
    val serviceFacade = new ServiceMockFacade(newTask)
    serviceFacade.plan()
    serviceFacade
  }

  def serviceProxy(appId: PathId, plan: String, withHealth: Boolean): AppDefinition = {
    AppDefinition(
      id = appId,
      cmd = Some(s"""$serviceMockScript '$plan'"""),
      executor = "//cmd",
      resources = Resources(cpus = 0.01, mem = 128.0),
      upgradeStrategy = UpgradeStrategy(0, 0),
      portDefinitions = Seq(PortDefinition(0, name = Some("http"))),
      healthChecks =
        if (withHealth)
          Set(
          MarathonHttpHealthCheck(
            path = Some("/ping"),
            portIndex = Some(PortReference(0)),
            maxConsecutiveFailures = Int.MaxValue,
            interval = 2.seconds,
            timeout = 1.second))
        else Set.empty[HealthCheck],
      readinessChecks = Seq(ReadinessCheck(
        "ready",
        portName = "http",
        path = "/v1/plan",
        interval = 2.seconds,
        timeout = 1.second,
        preserveLastResponse = true))
    )
  }

  /**
    * Create a shell script that can start a service mock
    */
  private lazy val serviceMockScript: String = {
    val uuid = UUID.randomUUID.toString
    appProxyIds(_ += uuid)
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[ServiceMock].getName
    val run = s"""$javaExecutable -DtestSuite=$suiteName -DappProxyId=$uuid -Xmx64m -classpath $classPath $main"""
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
