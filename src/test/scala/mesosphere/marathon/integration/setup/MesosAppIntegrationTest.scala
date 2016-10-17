package mesosphere.marathon.integration.setup

import mesosphere.marathon.core.pod.{ HostNetwork, HostVolume, MesosContainer, PodDefinition }
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.ProcessKeeper.MesosConfig
import mesosphere.marathon.raml
import mesosphere.marathon.state.{ AppDefinition, Container }
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class MesosAppIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen
    with RunInEnvironment {

  // Integration tests using docker image provisioning with the Mesos containerizer need to be
  // run as root in a Linux environment. They have to be explicitly enabled through an env variable.
  override val envVar = "RUN_MESOS_INTEGRATION_TESTS"

  // Configure Mesos to provide the Mesos containerizer with Docker image support.
  override def startMesos(): Unit = {
    ProcessKeeper.startMesosLocal(MesosConfig(
      port = config.mesosPort,
      launcher = "linux",
      containerizers = "mesos",
      isolation = Some("filesystem/linux,docker/runtime"),
      imageProviders = Some("docker")))
  }

  //clean up state before running the test case
  before(cleanUp())

  test("deploy a simple Docker app using the Mesos containerizer") {
    Given("a new Docker app")
    val app = AppDefinition(
      id = testBasePath / "mesosdockerapp",
      cmd = Some("sleep 600"),
      container = Some(Container.MesosDocker(image = "busybox")),
      resources = raml.Resources(cpus = 0.2, mem = 16.0),
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

  test("deploy a simple pod") {
    Given("a pod with a single task")
    val podId = testBasePath / "simplepod"

    val pod = PodDefinition(
      id = podId,
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand("sleep 1000"))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0)
        )
      ),
      networks = Seq(HostNetwork),
      instances = 1
    )

    When("The pod is deployed")
    val createResult = marathon.createPodV2(pod)

    Then("The pod is created")
    createResult.code should be(201) // Created
    waitForEvent("deployment_success")
    waitForPod(pod.id)

    When("The pod should be scaled")
    val scaledPod = pod.copy(instances = 2)
    val updateResult = marathon.updatePod(pod.id, scaledPod)

    Then("The pod is scaled")
    updateResult.code should be(200)
    waitForEvent("deployment_success")

    When("The pod should be deleted")
    val deleteResult = marathon.deletePod(pod.id)

    Then("The pod is deleted")
    deleteResult.code should be (202) // Deleted
    waitForEvent("deployment_success")
  }

  test("deploy a simple pod with health checks") {
    val projectDir = sys.props.getOrElse("user.dir", ".")
    val homeDir = sys.props.getOrElse("user.home", "~")

    Given("a pod with two tasks that are health checked")
    val podId = testBasePath / "healthypod"
    val containerDir = "/opt/marathon"
    def appMockCommand(port: String) = appProxyCommand(podId, "v1", containerDir, port)

    val pod = PodDefinition(
      id = podId,
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK1")))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
          image = Some(raml.Image(raml.ImageType.Docker, "openjdk:8-jre-alpine")),
          healthCheck = Some(raml.HealthCheck(http = Some(raml.HttpHealthCheck("task1", Some("/"))))),
          volumeMounts = Seq(
            raml.VolumeMount("target", s"$containerDir/target", Some(true)),
            raml.VolumeMount("ivy2", s"$containerDir/.ivy2", Some(true)),
            raml.VolumeMount("sbt", s"$containerDir/.sbt", Some(true))
          )
        ),
        MesosContainer(
          name = "task2",
          exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK2")))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task2", hostPort = Some(0))),
          image = Some(raml.Image(raml.ImageType.Docker, "openjdk:8-jre-alpine")),
          healthCheck = Some(raml.HealthCheck(http = Some(raml.HttpHealthCheck("task2", Some("/"))))),
          volumeMounts = Seq(
            raml.VolumeMount("target", s"$containerDir/target", Some(true)),
            raml.VolumeMount("ivy2", s"$containerDir/.ivy2", Some(true)),
            raml.VolumeMount("sbt", s"$containerDir/.sbt", Some(true))
          )
        )
      ),
      podVolumes = Seq(
        HostVolume("target", s"$projectDir/target"),
        HostVolume("ivy2", s"$homeDir/.ivy2"),
        HostVolume("sbt", s"$homeDir/.sbt")
      ),
      networks = Seq(HostNetwork),
      instances = 1
    )

    val check = appProxyCheck(pod.id, "v1", true)

    When("The pod is deployed")
    val createResult = marathon.createPodV2(pod)

    Then("The pod is created")
    createResult.code should be(201) //Created
    // The timeout is 5 minutes because downloading and provisioning the Python image can take some time.
    waitForEvent("deployment_success", 300.seconds)
    waitForPod(podId)
    check.pingSince(5.seconds) should be(true) //make sure, the app has really started

    When("The pod definition is changed")
    val updatedPod = pod.copy(
      containers = pod.containers :+ MesosContainer(
      name = "task3",
      exec = Some(raml.MesosExec(raml.ShellCommand("sleep 1000"))),
      resources = raml.Resources(cpus = 0.1, mem = 32.0)
    )
    )
    val updateResult = marathon.updatePod(pod.id, updatedPod)

    Then("The pod is updated")
    updateResult.code should be(200)
    waitForEvent("deployment_success")

    When("The pod should be deleted")
    val deleteResult = marathon.deletePod(pod.id)

    Then("The pod is deleted")
    deleteResult.code should be (202) // Deleted
    waitForEvent("deployment_success")
  }
}
