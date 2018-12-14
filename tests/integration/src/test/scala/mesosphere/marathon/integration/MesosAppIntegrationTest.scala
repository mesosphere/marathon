package mesosphere.marathon
package integration

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator.UNIQUE
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.core.health.{MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.pod._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.integration.facades.AppMockFacade
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{HostVolume, PersistentVolume, VolumeMount}
import mesosphere.mesos.Constraints.hostnameField
import mesosphere.{AkkaIntegrationTest, WaitTestSupport, WhenEnvSet}
import org.scalatest.Inside
import play.api.libs.json.JsObject

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class MesosAppIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {
  // Configure Mesos to provide the Mesos containerizer with Docker image support.
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    isolation = Some("filesystem/linux,docker/runtime"),
    imageProviders = Some("docker"))

  "MesosApp" should {
    "deploy a simple Docker app using the Mesos containerizer" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a new Docker app")
      val app = App(
        id = (testBasePath / "mesos-simple-docker-app").toString,
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

    "deploy a simple Docker app that uses Entrypoint/Cmd using the Mesos containerizer" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a new Docker app the uses 'Cmd' in its Dockerfile")
      val app = raml.App(
        id = (testBasePath / "mesos-docker-app-with-entrypoint").toString,
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

      marathon.deleteApp(app.id.toPath) // Otherwise the container will restart during the test life time since "hello-world' image exits after printing it's message to stdout
    }

    "deploy a simple pod" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a pod with a single task")
      val pod = simplePod("simple-pod-with-single-task")

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)

      Then("The pod is created")
      createResult should be(Created)
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }

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

    "deploy a simple persistent pod" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a pod with a single task and a volume")
      val containerPath = "pst1"
      val pod = residentPod(
        id = "simple-persistent-pod",
        mountPath = containerPath,
        cmd = s"""echo "data" > $containerPath/data && while test -e $containerPath/data; do sleep 5; done""")

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)

      Then("The pod is created")
      createResult should be(Created)
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }
    }

    "recover a simple persistent pod" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a pod with a single task and a volume")
      val projectDir = sys.props.getOrElse("user.dir", ".")
      val containerDir = "marathon"
      val id = testBasePath / "recover-simple-persistent-pod"
      //val cmd = s"""echo hello >> $containerPath/data && ${appMockCmd(id, "v1")}"""
      def appMockCommand(port: String) =
        s"""
           |echo APP PROXY $$MESOS_TASK_ID RUNNING; \\
           |echo "hello" >> $containerDir/data/test; \\
           |$containerDir/python/app_mock.py $port $id v1 http://httpbin.org/anything
        """.stripMargin

      val pod = PodDefinition(
        id = id,
        containers = Seq(
          MesosContainer(
            name = "task1",
            exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK1")))),
            resources = raml.Resources(cpus = 0.1, mem = 32.0),
            endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task1")), path = Some("/ping"))),
            volumeMounts = Seq(
              VolumeMount(Some("python"), s"$containerDir/python", false),
              VolumeMount(Some("data"), s"$containerDir/data", true)
            )
          )
        ),
        volumes = Seq(
          HostVolume(Some("python"), s"$projectDir/src/test/resources/python"),
          PersistentVolume(Some("data"), state.PersistentVolumeInfo(size = 2l))
        ),
        unreachableStrategy = state.UnreachableDisabled,
        upgradeStrategy = state.UpgradeStrategy(0.0, 0.0),
        networks = Seq(HostNetwork),
        instances = 1
      )

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      val runningPod = eventually {
        marathon.status(pod.id) should be(Stable)
        val status = marathon.status(pod.id).value
        val hosts = status.instances.flatMap(_.agentHostname)
        hosts should have size(1)
        val ports = status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))
        ports should have size (1)
        val facade = AppMockFacade(hosts.head, ports.head)
        facade.get(s"/$containerDir/data/test").futureValue should be("hello\n")
        facade
      }

      And("the pod dies")
      runningPod.suicide().futureValue

      Then("failed pod recovers")
      eventually {
        marathon.status(pod.id) should be(Stable)
        runningPod.get(s"/$containerDir/data/test").futureValue should be("hello\nhello\n")
      }
    }

    "deploy a simple pod with health checks" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      val projectDir = sys.props.getOrElse("user.dir", ".")

      Given("a pod with two tasks that are health checked")
      val podId = testBasePath / "healthy-pod-with-two-tasks"
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
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task1")), path = Some("/health"))),
            volumeMounts = Seq(
              VolumeMount(Some("python"), s"$containerDir/python", true)
            )
          ),
          MesosContainer(
            name = "task2",
            exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK2")))),
            resources = raml.Resources(cpus = 0.1, mem = 32.0),
            endpoints = Seq(raml.Endpoint(name = "task2", hostPort = Some(0))),
            image = Some(raml.Image(raml.ImageType.Docker, "python:3.4.6-alpine")),
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task2")), path = Some("/health"))),
            volumeMounts = Seq(
              VolumeMount(Some("python"), s"$containerDir/python", true)
            )
          )
        ),
        volumes = Seq(
          HostVolume(Some("python"), s"$projectDir/src/test/resources/python")
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
      eventually { marathon.status(pod.id) should be(Stable) }
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

    "deploy a pod with Entrypoint/Cmd" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("A pod using the 'hello' image that sets Cmd in its Dockerfile")
      val pod = simplePod("simple-pod-with-hello-image-and-cmd").copy(
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
      eventually { marathon.status(pod.id) should be(Stable) }

      marathon.deletePod(pod.id) // Otherwise the container will restart during the test life time since "hello-world' image exits after printing it's message to stdout
    }

    "deleting a group deletes pods deployed in the group" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a deployed pod")
      val parentGroup = testBasePath / "foo"
      val pod = simplePod(parentGroup.toString + "/simple-pod-is-deleted-with-group")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }
      marathon.listPodsInBaseGroupByPodId(pod.id).value should have size 1

      Then("The pod should show up as a group")
      val groupResult = marathon.group(parentGroup)
      groupResult should be(OK)

      When("The pod group is deleted")
      val deleteResult = marathon.deleteGroup(parentGroup)
      deleteResult should be(OK)
      waitForDeployment(deleteResult)

      Then("The pod is deleted")
      marathon.listDeploymentsForPathId(pod.id).value should have size 0
    }

    "list pod versions" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a new pod")
      val pod = simplePod("simple-pod-with-versions")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }

      When("The list of versions is fetched")
      val podVersions = marathon.listPodVersions(pod.id)

      Then("The response should contain all the versions")
      podVersions should be(OK)
      podVersions.value should have size 1
      podVersions.value.head should be(createResult.value.version)
    }

    "correctly version pods" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a new pod")
      val pod = simplePod("simple-pod-with-version-after-update")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      //Created
      val originalVersion = createResult.value.version
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }

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

    "stop (forcefully delete) a pod deployment" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a pod with constraints that cannot be fulfilled")
      val constraint = Protos.Constraint.newBuilder().setField("nonExistent").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("na").build()
      val pod = simplePod("simple-pod-with-impossible-constraints-force-delete").copy(
        constraints = Set(constraint)
      )

      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      val deploymentId = createResult.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)
      deploymentId shouldBe defined

      Then("the deployment gets created")
      WaitTestSupport.validFor("deployment visible", 5.second)(marathon.listDeploymentsForPathId(pod.id).value.size == 1)

      When("the deployment is deleted")
      val deleteResult = marathon.deleteDeployment(deploymentId.get, force = true)
      deleteResult should be(Deleted)

      Then("the deployment should be gone")
      waitForEvent("deployment_failed")
      WaitTestSupport.waitUntil("Deployments get removed from the queue") {
        marathon.listDeploymentsForPathId(pod.id).value.isEmpty
      }

      Then("the pod should still be there")
      marathon.pod(pod.id) should be(OK)
    }

    "rollback a pod deployment" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a pod with constraints that cannot be fulfilled")
      val constraint = Protos.Constraint.newBuilder().setField("nonExistent").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("na").build()
      val pod = simplePod("simple-pod-with-impossible-constraints-rollback").copy(
        constraints = Set(constraint)
      )

      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      val deploymentId = createResult.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)
      deploymentId shouldBe defined

      Then("the deployment gets created")
      WaitTestSupport.validFor("deployment visible", 5.second)(marathon.listDeploymentsForPathId(pod.id).value.size == 1)

      When("the deployment is rolled back")
      val deleteResult = marathon.deleteDeployment(deploymentId.get)
      deleteResult should be(OK)
      val deleteId = deleteResult.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)
      deleteId shouldBe defined

      Then("the deployment should be gone")
      val waitingFor = Map[String, CallbackEvent => Boolean](
        "deployment_failed" -> (_.id == deploymentId.value), // StartPod
        "deployment_success" -> (_.id == deleteId.value) // StopPod
      )
      waitForEventsWith(s"waiting for canceled ${deploymentId.value} and successful ${deleteId.value}", waitingFor)

      WaitTestSupport.waitUntil("Deployments get removed from the queue") {
        marathon.listDeploymentsForPathId(pod.id).value.isEmpty
      }

      Then("the pod should also be gone")
      marathon.pod(pod.id) should be(NotFound)
    }

    "delete pod instances" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
      Given("a new pod with 2 instances")
      val pod = simplePod("simple-pod-with-two-instances-delete").copy(
        instances = 2
      )

      When("The pod is created")
      val createResult = marathon.createPodV2(pod)
      createResult should be(Created)
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }

      Then("Two instances should be running")
      val originalStatus = marathon.status(pod.id)
      originalStatus should be(OK)
      originalStatus.value.instances should have size 2

      When("An instance is deleted")
      val instance = originalStatus.value.instances.head
      val instanceId = instance.id
      inside(marathon.deleteInstance(pod.id, instanceId)) {
        case deleteResult =>
          deleteResult should be(OK)
      }

      Then("The deleted instance should be restarted")
      waitForStatusUpdates("TASK_KILLED", "TASK_RUNNING")
      eventually {
        val status = marathon.status(pod.id)
        status should be(Stable)
        status.value.instances should have size 2
      }

      Then("The restarted instance should have incremented the incarnation")
      val postRestartStatus = marathon.status(pod.id)
      postRestartStatus should be(OK)
      val Some(nextInstance) = postRestartStatus.value.instances.find(_.id == instanceId)
      println(nextInstance)

      inside((instance.containers.head.containerId.map(Task.Id.parse(_)), nextInstance.containers.head.containerId.map(Task.Id.parse(_)))) {
        case (Some(former: Task.TaskIdWithIncarnation), Some(latter: Task.TaskIdWithIncarnation)) =>
          former.incarnation should be < latter.incarnation
      }

      When("An instance is deleted with wipe=true")
      inside(marathon.deleteInstance(pod.id, instanceId, wipe = true)) {
        case deleteResult =>
          deleteResult should be(OK)
      }

      Then("a new pod instance should be created with a new instanceId")
      eventually {
        val status = marathon.status(pod.id)
        status should be(Stable)
        status.value.instances should have size 2
        status.value.instances.map(_.id) shouldNot contain(instanceId)
      }
    }

    val pods = List(
      residentPod("resident-pod-with-one-instance-wipe").copy(instances = 1) -> "persistent",
      simplePod("simple-pod-with-one-instance-wipe-test").copy(instances = 1) -> "simple"
    )

    pods.foreach {
      case (pod, podType) =>
        s"wipe $podType pod instance" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
          Given(s"a $podType pod")

          When(s"The $podType pod is created")
          val createResult = marathon.createPodV2(pod)
          createResult should be(Created)
          waitForDeployment(createResult)

          Then("pod status should be stable")
          eventually {
            marathon.status(pod.id) should be(Stable)
          }

          When("Pods instance is deleted with wipe=true")
          val status = marathon.status(pod.id)
          val instanceId = status.value.instances.head.id
          val deleteResult = marathon.deleteInstance(pod.id, instanceId, wipe = true)
          deleteResult should be(OK)

          Then("pod instance is erased from marathon's knowledge ")
          val knownInstanceIds = marathon.status(pod.id).value.instances.map(_.id)
          knownInstanceIds should not contain instanceId

          And(s"a new pod ${if (podType == "persistent") "with a new persistent volume " else ""}is scheduled")
          waitForStatusUpdates("TASK_RUNNING")
          eventually {
            val status = marathon.status(pod.id)
            status.value.instances should have size 1
            status.value.instances.map(_.id) should not contain instanceId
          }
        }
    }

    "deploy a simple pod with unique constraint and then " taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {

      val constraints = Set(
        Constraint.newBuilder
          .setField(hostnameField)
          .setOperator(UNIQUE)
          .build
      )

      Given("a pod with a single task")
      val podName = "simple-pod-with-unique-constraint"
      val pod = simplePod(podName, constraints = constraints, instances = 1)

      When("The pod is deployed")
      val createResult = marathon.createPodV2(pod)

      Then("The pod is created")
      createResult should be(Created)
      waitForDeployment(createResult)
      eventually { marathon.status(pod.id) should be(Stable) }

      When("The pod config is updated")
      val scaledPod = pod.copy(instances = 2)
      val updateResult = marathon.updatePod(pod.id, scaledPod, force = true)

      Then("The pod is not scaled")
      updateResult should be(OK)
      def queueResult = marathon.launchQueue()
      def jsQueueResult = queueResult.entityJson

      def queuedRunspecs = (jsQueueResult \ "queue").as[Seq[JsObject]]
      def jsonPod = queuedRunspecs.find { spec => (spec \ "pod" \ "id").as[String] == s"/$podName" }.get

      def unfulfilledConstraintRejectSummary = (jsonPod \ "processedOffersSummary" \ "rejectSummaryLastOffers").as[Seq[JsObject]]
        .find { e => (e \ "reason").as[String] == "UnfulfilledConstraint" }.get

      And("unique constraint reject must happen")
      eventually {
        (unfulfilledConstraintRejectSummary \ "declined").as[Int] should be >= 1
      }

      And("Size of the pod should still be 1")
      val status2 = marathon.status(pod.id)
      status2 should be(OK)
      //we have only one agent by default, so we expect one instance to be running.
      status2.value.instances should have size 1

    }
  }
}
