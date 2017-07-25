package mesosphere.marathon
package integration

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.core.health.{MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.pod._
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig, WaitTestSupport}
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType}
import mesosphere.marathon.state.PathId._
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.duration._

@IntegrationTest
class MesosAppIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  // Integration tests using docker image provisioning with the Mesos containerizer need to be
  // run as root in a Linux environment. They have to be explicitly enabled through an env variable.
  val envVar = "RUN_MESOS_INTEGRATION_TESTS"

  val currentAppId = new AtomicInteger()

  // Configure Mesos to provide the Mesos containerizer with Docker image support.
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    isolation = Some("filesystem/linux,docker/runtime"),
    imageProviders = Some("docker"))

  private[this] def simplePod(podId: String): PodDefinition = PodDefinition(
    id = testBasePath / s"$podId-${currentAppId.incrementAndGet()}",
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

  //clean up state before running the test case
  before(cleanUp())

  "MesosApp" should {
    "deploy a simple Docker app using the Mesos containerizer" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new Docker app")
      val app = App(
        id = (testBasePath / s"mesos-docker-app-${currentAppId.incrementAndGet()}").toString,
        cmd = Some("sleep 600"),
        container = Some(Container(`type` = EngineType.Mesos, docker = Some(DockerContainer(image = "busybox")))),
        cpus = 0.2,
        mem = 16.0

      )

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1) // The app has really started
    }

    "deploy a simple Docker app that uses Entrypoint/Cmd using the Mesos containerizer" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new Docker app the uses 'Cmd' in its Dockerfile")
      val app = raml.App(
        id = (testBasePath / s"mesos-docker-app-${currentAppId.incrementAndGet()}").toString,
        container = Some(raml.Container(`type` = raml.EngineType.Mesos, docker = Some(raml.DockerContainer(
          image = "hello-world")))),
        cpus = 0.1, mem = 32.0,
        instances = 1
      )

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1) // The app has really started
    }

    "deploy a simple pod" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a pod with a single task")
      val pod = simplePod("simplepod")

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)

      Then("The pod is created")
      createResult should be(Created)
      waitForDeployment(createResult)
      waitForPod(pod.id)

      When("The pod should be scaled")
      val scaledPod = pod.copy(instances = 2)
      val updateResult = marathon.updatePod(pod.id, scaledPod)

      Then("The pod is scaled")
      updateResult should be(OK)
      waitForDeployment(updateResult)

      When("The pod should be deleted")
      val deleteResult = marathon.deletePod(pod.id)

      Then("The pod is deleted")
      deleteResult should be(Deleted)
      waitForDeployment(deleteResult)
    }

    "deploy a simple pod with health checks" taggedAs WhenEnvSet(envVar, default = "true") in {
      val projectDir = sys.props.getOrElse("user.dir", ".")

      Given("a pod with two tasks that are health checked")
      val podId = testBasePath / s"healthypod-${currentAppId.incrementAndGet()}"
      val containerDir = "/opt/marathon"

      def appMockCommand(port: String) = """echo APP PROXY $$MESOS_TASK_ID RUNNING; /opt/marathon/python/app_mock.py """ +
        s"""$port $podId v1 ${healthEndpointFor(podId, "v1")}"""

      val pod = PodDefinition(
        id = podId,
        containers = Seq(
          MesosContainer(
            name = "task1",
            exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK1")))),
            resources = raml.Resources(cpus = 0.1, mem = 32.0),
            endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
            image = Some(raml.Image(raml.ImageType.Docker, "python:3.4.6-alpine")),
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task1")), path = Some("/"))),
            volumeMounts = Seq(
              VolumeMount("python", s"$containerDir/python", Some(true))
            )
          ),
          MesosContainer(
            name = "task2",
            exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK2")))),
            resources = raml.Resources(cpus = 0.1, mem = 32.0),
            endpoints = Seq(raml.Endpoint(name = "task2", hostPort = Some(0))),
            image = Some(raml.Image(raml.ImageType.Docker, "python:3.4.6-alpine")),
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task2")), path = Some("/"))),
            volumeMounts = Seq(
              VolumeMount("python", s"$containerDir/python", Some(true))
            )
          )
        ),
        podVolumes = Seq(
          HostVolume("python", s"$projectDir/src/test/python")
        ),
        networks = Seq(HostNetwork),
        instances = 1
      )

      val check = registerAppProxyHealthCheck(pod.id, "v1", state = true)

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)

      Then("The pod is created")
      createResult should be(Created)
      waitForDeployment(createResult)
      waitForPod(podId)
      check.pinged.set(false)
      eventually {
        check.pinged.get should be(true) withClue "App did not start"
      }

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
      updateResult should be(OK)
      waitForDeployment(updateResult)

      When("The pod should be deleted")
      val deleteResult = marathon.deletePod(pod.id)

      Then("The pod is deleted")
      deleteResult should be(Deleted)
      waitForDeployment(deleteResult)
    }

    "deploy a pod with Entrypoint/Cmd" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("A pod using the 'hello' image that sets Cmd in its Dockerfile")
      val pod = simplePod("simplepod").copy(
        containers = Seq(MesosContainer(
          name = "hello",
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          image = Some(raml.Image(raml.ImageType.Docker, "hello-world"))
        ))
      )

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)

      Then("The pod is created")
      createResult should be(Created)
      waitForDeployment(createResult)
      waitForPod(pod.id)
    }

    "deleting a group deletes pods deployed in the group" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a deployed pod")
      val pod = simplePod("simplepod")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      waitForPod(pod.id)
      marathon.listPodsInBaseGroup.value should have size 1

      Then("The pod should show up as a group")
      val groupResult = marathon.group(testBasePath)
      groupResult should be(OK)

      When("The pod group is deleted")
      val deleteResult = marathon.deleteGroup(testBasePath)
      deleteResult should be(OK)
      waitForDeployment(deleteResult)

      Then("The pod is deleted")
      marathon.listPodsInBaseGroup.value should have size 0
    }

    "list pod versions" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new pod")
      val pod = simplePod("simplepod")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      waitForPod(pod.id)

      When("The list of versions is fetched")
      val podVersions = marathon.listPodVersions(pod.id)

      Then("The response should contain all the versions")
      podVersions should be(OK)
      podVersions.value should have size 1
      podVersions.value.head should be(createResult.value.version)
    }

    "correctly version pods" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new pod")
      val pod = simplePod("simplepod")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      //Created
      val originalVersion = createResult.value.version
      waitForDeployment(createResult)
      waitForPod(pod.id)

      When("A task is added to the pod")
      val updatedPod = pod.copy(
        containers = pod.containers :+ MesosContainer(
        name = "task3",
        exec = Some(raml.MesosExec(raml.ShellCommand("sleep 1000"))),
        resources = raml.Resources(cpus = 0.1, mem = 32.0)
      )
      )
      val updateResult = marathon.updatePod(pod.id, updatedPod)
      updateResult should be(OK)
      val updatedVersion = updateResult.value.version
      waitForDeployment(updateResult)

      Then("It should create a new version with the right data")
      val originalVersionResult = marathon.podVersion(pod.id, originalVersion)
      originalVersionResult should be(OK)
      originalVersionResult.value.containers should have size 1

      val updatedVersionResult = marathon.podVersion(pod.id, updatedVersion)
      updatedVersionResult should be(OK)
      updatedVersionResult.value.containers should have size 2
    }

    "stop (forcefully delete) a pod deployment" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a pod with constraints that cannot be fulfilled")
      val constraint = Protos.Constraint.newBuilder().setField("nonExistent").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("na").build()
      val pod = simplePod("simplepod").copy(
        constraints = Set(constraint)
      )

      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      val deploymentId = createResult.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)
      deploymentId shouldBe defined

      Then("the deployment gets created")
      WaitTestSupport.validFor("deployment visible", 5.second)(marathon.listDeploymentsForBaseGroup().value.size == 1)

      When("the deployment is deleted")
      val deleteResult = marathon.deleteDeployment(deploymentId.get, force = true)
      deleteResult should be(Deleted)

      Then("the deployment should be gone")
      waitForEvent("deployment_failed")
      WaitTestSupport.waitUntil("Deployments get removed from the queue") {
        marathon.listDeploymentsForBaseGroup().value.isEmpty
      }

      Then("the pod should still be there")
      marathon.pod(pod.id) should be(OK)
    }

    "rollback a pod deployment" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a pod with constraints that cannot be fulfilled")
      val constraint = Protos.Constraint.newBuilder().setField("nonExistent").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("na").build()
      val pod = simplePod("simplepod").copy(
        constraints = Set(constraint)
      )

      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      val deploymentId = createResult.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)
      deploymentId shouldBe defined

      Then("the deployment gets created")
      WaitTestSupport.validFor("deployment visible", 5.second)(marathon.listDeploymentsForBaseGroup().value.size == 1)

      When("the deployment is rolled back")
      val deleteResult = marathon.deleteDeployment(deploymentId.get)
      deleteResult should be(OK)
      val deleteId = deleteResult.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)
      deleteId shouldBe defined

      Then("the deployment should be gone")
      val waitingFor = mutable.Map[String, CallbackEvent => Boolean](
        "deployment_failed" -> (_.id == deploymentId.value), // StartPod
        "deployment_success" -> (_.id == deleteId.value) // StopPod
      )
      waitForEventsWith(s"waiting for canceled ${deploymentId.value} and successful ${deleteId.value}", waitingFor)

      WaitTestSupport.waitUntil("Deployments get removed from the queue") {
        marathon.listDeploymentsForBaseGroup().value.isEmpty
      }

      Then("the pod should also be gone")
      marathon.pod(pod.id) should be(NotFound)
    }

    "delete pod instances" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new pod with 2 instances")
      val pod = simplePod("simplepod").copy(
        instances = 3
      )

      When("The pod is created")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      waitForPod(pod.id)

      Then("Three instances should be running")
      val status1 = marathon.status(pod.id)
      status1 should be(OK)
      status1.value.instances should have size 3

      When("An instance is deleted")
      val instanceId = status1.value.instances.head.id
      val deleteResult1 = marathon.deleteInstance(pod.id, instanceId)
      deleteResult1 should be(OK)

      Then("The deleted instance should be restarted")
      waitForStatusUpdates("TASK_KILLED", "TASK_RUNNING")
      val status2 = marathon.status(pod.id)
      status2 should be(OK)
      status2.value.instances.filter(_.status == raml.PodInstanceState.Stable) should have size 3
    }
  }
}
