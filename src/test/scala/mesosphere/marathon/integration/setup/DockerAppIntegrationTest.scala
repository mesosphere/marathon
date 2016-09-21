package mesosphere.marathon.integration.setup

import mesosphere.marathon.integration.facades.MarathonFacade
import MarathonFacade._
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{ AppDefinition, Container }
import mesosphere.marathon.integration.setup.ProcessKeeper.MesosConfig
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.concurrent.duration._

class DockerAppIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen
    with RunInEnvironment {

  // FIXME (gkleiman): Docker tests don't work under Docker Machine yet. So they can be disabled through an env variable.
  override val envVar = "RUN_DOCKER_INTEGRATION_TESTS"

  // Configure Mesos to provide the Docker containerizer.
  override def startMesos(): Unit = {
    ProcessKeeper.startMesosLocal(MesosConfig(
      port = config.mesosPort,
      containerizers = "docker,mesos"))
  }

  //clean up state before running the test case
  before(cleanUp())

  test("deploy a simple Docker app") {
    Given("a new Docker app")
    val app = AppDefinition(
      id = testBasePath / "dockerapp",
      cmd = Some("sleep 600"),
      container = Some(Container.Docker(image = "busybox")),
      resources = Resources(cpus = 0.2, mem = 16.0),
      instances = 1
    )

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be(201) // Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
    waitForTasks(app.id, 1) // The app has really started
  }

  test("create a simple docker app using http health checks with HOST networking") {
    Given("a new app")
    val app = dockerAppProxy(testBasePath / "docker-http-app", "v1", instances = 1, withHealth = true)
    val check = appProxyCheck(app.id, "v1", true)

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be(201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
    check.pingSince(5.seconds) should be(true) //make sure, the app has really started
  }
}
