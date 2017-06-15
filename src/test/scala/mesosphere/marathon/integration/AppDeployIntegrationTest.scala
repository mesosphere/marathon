package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.facades.{ ITDeployment, ITEnrichedTask, ITQueueItem }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.{ App, AppHealthCheck, AppHealthCheckProtocol, AppUpdate, CommandCheck, Container, ContainerPortMapping, DockerContainer, EngineType, Network, NetworkMode, NetworkProtocol }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.control.NonFatal

@IntegrationTest
class AppDeployIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //clean up state before running the test case
  before(cleanUp())

  def appId(): PathId = testBasePath / s"app-${UUID.randomUUID}"

  "AppDeploy" should {
    "create a simple app without health checks" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1) //make sure, the app has really started
    }

    "redeploying an app without changes should not cause restarts" in {
      Given("an deployed app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)
      val result = marathon.createAppV2(app)
      result.code should be(201) //Created
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      val taskBeforeRedeployment = waitForTasks(app.id.toPath, 1) //make sure, the app has really started

      When("redeploying the app without changes")
      val update = marathon.updateApp(app.id.toPath, AppUpdate(id = Some(app.id), cmd = app.cmd))
      waitForDeployment(update)
      val tasksAfterRedeployment = waitForTasks(app.id.toPath, 1) //make sure, the app has really started

      Then("no tasks should have been restarted")
      taskBeforeRedeployment should be(tasksAfterRedeployment)
    }

    "backoff delays are reset on configuration changes" in {
      val app: App = createAFailingAppResultingInBackOff()

      When("we force deploy a working configuration")
      val deployment2 = marathon.updateApp(app.id.toPath, AppUpdate(cmd = Some("sleep 120; true")), force = true)

      Then("The app deployment is created")
      deployment2 should be(OK)

      And("and the app gets deployed immediately")
      waitForDeployment(deployment2)
      waitForTasks(app.id.toPath, 1)
    }

    "backoff delays are NOT reset on scaling changes" in {
      val app: App = createAFailingAppResultingInBackOff()

      When("we force deploy a scale change")
      val deployment2 = marathon.updateApp(app.id.toPath, AppUpdate(instances = Some(3)), force = true)

      Then("The app deployment is created")
      deployment2 should be(OK)

      And("BUT our app still has a backoff delay")
      val queueAfterScaling: List[ITQueueItem] = marathon.launchQueue().value.queue
      queueAfterScaling should have size 1
      queueAfterScaling.map(_.delay.overdue) should contain(false)
    }

    "restarting an app with backoff delay starts immediately" in {
      val app: App = createAFailingAppResultingInBackOff()

      When("we force a restart")
      val deployment2 = marathon.restartApp(app.id.toPath, force = true)

      Then("The app deployment is created")
      deployment2 should be(OK)

      And("the task eventually fails AGAIN")
      waitForStatusUpdates("TASK_RUNNING", "TASK_FAILED")
    }

    def createAFailingAppResultingInBackOff(): App = {
      Given("a new app")
      val app =
        appProxy(appId(), "v1", instances = 1, healthCheck = None)
          .copy(
            cmd = Some("false"),
            backoffSeconds = 1.hour.toSeconds.toInt,
            maxLaunchDelaySeconds = 1.hour.toSeconds.toInt
          )

      When("we request to deploy the app")
      val result = marathon.createAppV2(app)

      Then("The app deployment is created")
      result should be(Created)

      And("the task eventually fails")
      waitForStatusUpdates("TASK_RUNNING", "TASK_FAILED")

      And("our app gets a backoff delay")
      WaitTestSupport.waitUntil("queue item") {
        try {
          val queue: List[ITQueueItem] = marathon.launchQueue().value.queue
          queue should have size 1
          queue.map(_.delay.overdue) should contain(false)
          true
        } catch {
          case NonFatal(e) =>
            log.info("while querying queue", e)
            false
        }
      }
      app
    }

    // OK
    "increase the app count metric when an app is created" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)

      val appCount = (marathon.metrics().entityJson \ "gauges" \ "service.mesosphere.marathon.app.count" \ "mean").as[Double]

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app count metric should increase")
      result should be(Created)
      eventually {
        (marathon.metrics().entityJson \ "gauges" \ "service.mesosphere.marathon.app.count" \ "max").as[Double] should be > appCount
      }
    }

    // OK
    "create a simple app without health checks via secondary (proxying)" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1) //make sure, the app has really started
    }

    "create a simple app with a Marathon HTTP health check" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None).
        copy(healthChecks = Set(ramlHealthCheck))
      val check = registerAppProxyHealthCheck(PathId(app.id), "v1", state = true)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      check.pinged.set(false)
      eventually {
        check.pinged.get should be(true) withClue "App did not start"
      }
    }

    "create a simple app with a Mesos HTTP health check" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None).
        copy(healthChecks = Set(ramlHealthCheck.copy(protocol = AppHealthCheckProtocol.MesosHttp)))
      val check = registerAppProxyHealthCheck(app.id.toPath, "v1", state = true)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      check.pinged.set(false)
      eventually {
        check.pinged.get should be(true) withClue "App did not start"
      }
    }

    "create a simple app with a Marathon HTTP health check using port instead of portIndex" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None).
        copy(
          portDefinitions = Option(raml.PortDefinitions(31000)),
          requirePorts = true,
          healthChecks = Set(ramlHealthCheck.copy(port = Some(31000), portIndex = None))
        )
      val check = registerAppProxyHealthCheck(app.id.toPath, "v1", state = true)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      check.pinged.set(false)
      eventually {
        check.pinged.get should be(true) withClue "App did not start"
      }
    }

    "create a simple app with a Marathon TCP health check" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None).
        copy(healthChecks = Set(ramlHealthCheck.copy(protocol = AppHealthCheckProtocol.Tcp)))

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
    }

    "create a simple app with a Mesos TCP healh check" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None).
        copy(healthChecks = Set(ramlHealthCheck.copy(protocol = AppHealthCheckProtocol.Tcp)))

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
    }

    "create a simple app with a COMMAND health check" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None).
        copy(healthChecks = Set(AppHealthCheck(
          protocol = AppHealthCheckProtocol.Command,
          command = Some(CommandCheck("true")))))

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
    }

    // OK
    "list running apps and tasks" in {
      Given("a new app is deployed")
      val app = appProxy(appId(), "v1", instances = 2, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)

      When("the deployment has finished")
      waitForDeployment(create)

      Then("the list of running app tasks can be fetched")
      val apps = marathon.listAppsInBaseGroup
      apps should be(OK)
      apps.value should have size 1

      val tasksResult: RestResult[List[ITEnrichedTask]] = marathon.tasks(app.id.toPath)
      tasksResult should be(OK)

      val tasks = tasksResult.value
      tasks should have size 2
    }

    "an unhealthy app fails to deploy" in {
      Given("a new app that is not healthy")
      val id = appId()
      registerAppProxyHealthCheck(id, "v1", state = false)
      val app = appProxy(id, "v1", instances = 1, healthCheck = Some(appProxyHealthCheck()))

      When("The app is deployed")
      val create = marathon.createAppV2(app)

      Then("We receive a deployment created confirmation")
      create should be(Created)
      extractDeploymentIds(create) should have size 1

      And("a number of failed health events but the deployment does not succeed")

      def interestingEvent() = waitForEventMatching("failed_health_check_event or deployment_success")(callbackEvent =>
        callbackEvent.eventType == "deployment_success" ||
          callbackEvent.eventType == "failed_health_check_event"
      )

      for (event <- Iterator.continually(interestingEvent()).take(10)) {
        event.eventType should be("failed_health_check_event")
      }

      When("The app is deleted")
      val delete = marathon.deleteApp(id, force = true)
      delete should be(OK)
      waitForDeployment(delete)
      marathon.listAppsInBaseGroup.value should have size 0
    }

    "update an app" in {
      Given("a new app")
      val id = appId()
      val v1 = appProxy(id, "v1", instances = 1, healthCheck = Some(appProxyHealthCheck()))
      val create = marathon.createAppV2(v1)
      create should be(Created)
      waitForDeployment(create)
      val before = marathon.tasks(id)

      When("The app is updated")
      val check = registerAppProxyHealthCheck(id, "v2", state = true)
      val update = marathon.updateApp(PathId(v1.id), AppUpdate(cmd = appProxy(id, "v2", 1).cmd))

      Then("The app gets updated")
      update should be(OK)
      waitForDeployment(update)
      waitForTasks(id, before.value.size)
      check.pinged.set(false)
      eventually {
        check.pinged.get should be(true) withClue "App did not start"
      }
    }

    "update an app through patch request" in {
      Given("a new app")
      val appId = testBasePath / "app"
      val v1 = appProxy(appId, "v1", instances = 1, healthCheck = Some(appProxyHealthCheck()))
      val create = marathon.createAppV2(v1)
      create should be(Created)
      waitForDeployment(create)
      val before = marathon.tasks(appId)

      When("The app is updated")
      val check = registerAppProxyHealthCheck(appId, "v2", state = true)
      val update = marathon.patchApp(v1.id.toPath, AppUpdate(cmd = appProxy(appId, "v2", 1).cmd))

      Then("The app gets updated")
      update should be(OK)
      waitForDeployment(update)
      waitForTasks(appId, before.value.size)
      check.pinged.set(false)
      eventually {
        check.pinged.get should be(true) withClue "App did not start"
      }

      Then("Check if healthcheck is not updated")
      val appResult = marathon.app(appId)
      appResult should be(OK)
      appResult.value.app.healthChecks
    }

    "scale an app up and down" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)
      waitForDeployment(create)

      When("The app gets an update to be scaled up")
      val scaleUp = marathon.updateApp(PathId(app.id), AppUpdate(instances = Some(2)))

      Then("New tasks are launched")
      scaleUp should be(OK)
      waitForDeployment(scaleUp)
      waitForTasks(app.id.toPath, 2)

      When("The app gets an update to be scaled down")
      val scaleDown = marathon.updateApp(PathId(app.id), AppUpdate(instances = Some(1)))

      Then("Tasks are killed")
      scaleDown should be(OK)
      waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")
      waitForTasks(app.id.toPath, 1)
    }

    "restart an app" in {
      Given("a new app")
      val id = appId()
      val v1 = appProxy(id, "v1", instances = 1, healthCheck = None)
      val create = marathon.createAppV2(v1)
      create should be(Created)
      waitForDeployment(create)
      val before = marathon.tasks(id)

      When("The app is restarted")
      val restart = marathon.restartApp(PathId(v1.id))

      Then("All instances of the app get restarted")
      restart should be(OK)
      waitForDeployment(restart)
      val after = marathon.tasks(id)
      waitForTasks(id, before.value.size)
      before.value.toSet should not be after.value.toSet
    }

    "list app versions" in {
      Given("a new app")
      val v1 = appProxy(appId(), "v1", instances = 1, healthCheck = None)
      val createResponse = marathon.createAppV2(v1)
      createResponse should be(Created)
      waitForDeployment(createResponse)

      When("The list of versions is fetched")
      val list = marathon.listAppVersions(v1.id.toPath)

      Then("The response should contain all the versions")
      list should be(OK)
      list.value.versions should have size 1
      list.value.versions.headOption should be(createResponse.value.version.map(Timestamp(_)))
    }

    "correctly version apps" in {
      Given("a new app")
      val v1 = appProxy(appId(), "v1", instances = 1, healthCheck = None)
      val createResponse = marathon.createAppV2(v1)
      createResponse.code should be(201)
      val originalVersion = createResponse.value.version
      waitForDeployment(createResponse)

      When("A resource specification is updated")
      val updatedDisk: Double = v1.disk + 1.0
      val appUpdate = AppUpdate(Option(v1.id), disk = Option(updatedDisk))
      val updateResponse = marathon.updateApp(PathId(v1.id), appUpdate)
      updateResponse should be(OK)
      waitForDeployment(updateResponse)

      Then("It should create a new version with the right data")
      val responseOriginalVersion = marathon.appVersion(v1.id.toPath, Timestamp(originalVersion.get))
      responseOriginalVersion should be(OK)
      responseOriginalVersion.value.disk should be(v1.disk)

      val updatedVersion = updateResponse.value.version
      val responseUpdatedVersion = marathon.appVersion(PathId(v1.id), updatedVersion)
      responseUpdatedVersion should be(OK)
      responseUpdatedVersion.value.disk should be(updatedDisk)
    }

    "kill a task of an App" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)
      waitForDeployment(create)
      val taskId = marathon.tasks(app.id.toPath).value.head.id

      When("a task of an app is killed")
      val response = marathon.killTask(PathId(app.id), taskId)
      response should be(OK)

      waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")

      Then("All instances of the app get restarted")
      waitForTasks(app.id.toPath, 1)
      marathon.tasks(app.id.toPath).value.head should not be taskId
    }

    "kill a task of an App with scaling" in {
      Given("a new app")
      val app = appProxy(appId(), "v1", instances = 2, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)
      waitForDeployment(create)
      val taskId = marathon.tasks(app.id.toPath).value.head.id

      When("a task of an app is killed and scaled")
      marathon.killTask(app.id.toPath, taskId, scale = true).code should be(200)
      waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")

      Then("All instances of the app get restarted")
      waitForTasks(app.id.toPath, 1)
      marathon.app(app.id.toPath).value.app.instances should be(1)
    }

    "kill all tasks of an App" in {
      Given("a new app with multiple tasks")
      val app = appProxy(appId(), "v1", instances = 2, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)
      waitForDeployment(create)

      When("all task of an app are killed")
      val response = marathon.killAllTasks(PathId(app.id))
      response should be(OK)
      waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")
      waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")

      Then("All instances of the app get restarted")
      waitForTasks(app.id.toPath, 2)
    }

    "kill all tasks of an App with scaling" in {
      Given("a new app with multiple tasks")
      val app = appProxy(appId(), "v1", instances = 2, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)
      waitForDeployment(create)
      marathon.app(app.id.toPath).value.app.instances should be(2)

      When("all task of an app are killed")
      val result = marathon.killAllTasksAndScale(app.id.toPath)
      result should be(OK)
      result.value.version should not be empty

      Then("All instances of the app get restarted")
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 0)
      marathon.app(app.id.toPath).value.app.instances should be(0)
    }

    "delete an application" in {
      Given("a new app with one task")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)
      val create = marathon.createAppV2(app)
      create should be(Created)
      waitForDeployment(create)

      When("the app is deleted")
      val delete = marathon.deleteApp(PathId(app.id))
      delete should be(OK)
      waitForDeployment(delete)

      Then("All instances of the app get restarted")
      marathon.listAppsInBaseGroup.value should have size 0
    }

    "create and deploy an app with two tasks" in {
      Given("a new app")
      val appIdPath: PathId = appId()
      val app = appProxy(appIdPath, "v1", instances = 2, healthCheck = None)

      When("the app gets posted")
      val createdApp: RestResult[App] = marathon.createAppV2(app)

      Then("the app is created and a success event arrives eventually")
      createdApp should be(Created)

      Then("we get various events until deployment success")
      val deploymentIds: Seq[String] = extractDeploymentIds(createdApp)
      deploymentIds.length should be(1)
      val deploymentId = deploymentIds.head

      val events: Map[String, Seq[CallbackEvent]] = waitForEvents(
        "api_post_event", "group_change_success", "deployment_info",
        "status_update_event", "status_update_event",
        "deployment_success")(patienceConfig.timeout)

      val Seq(apiPostEvent) = events("api_post_event")
      apiPostEvent.info("appDefinition").asInstanceOf[Map[String, Any]]("id").asInstanceOf[String] should be(appIdPath.toString)

      val Seq(groupChangeSuccess) = events("group_change_success")
      groupChangeSuccess.info("groupId").asInstanceOf[String] should be(appIdPath.parent.toString)

      val Seq(taskUpdate1, taskUpdate2) = events("status_update_event")
      taskUpdate1.info("appId").asInstanceOf[String] should be(appIdPath.toString)
      taskUpdate2.info("appId").asInstanceOf[String] should be(appIdPath.toString)

      val Seq(deploymentSuccess) = events("deployment_success")
      deploymentSuccess.info("id") should be(deploymentId)

      Then("after that deployments should be empty")
      val event: RestResult[List[ITDeployment]] = marathon.listDeploymentsForBaseGroup()
      event.value should be('empty)

      Then("Both tasks respond to http requests")

      def pingTask(taskInfo: CallbackEvent): String = {
        val host: String = taskInfo.info("host").asInstanceOf[String]
        val port: Int = taskInfo.info("ports").asInstanceOf[Seq[Int]].head
        appMock.ping(host, port).futureValue.asString
      }

      pingTask(taskUpdate1) should be(s"Pong $appIdPath")
      pingTask(taskUpdate2) should be(s"Pong $appIdPath")
    }

    "stop (forcefully delete) a deployment" in {
      Given("a new app with constraints that cannot be fulfilled")
      val c = Seq("nonExistent", "CLUSTER", "na")
      val id = appId()
      val app = App(id.toString, constraints = Set(c), cmd = Some("na"), instances = 5, portDefinitions = None)

      val create = marathon.createAppV2(app)
      create should be(Created)
      // Created
      val deploymentId = extractDeploymentIds(create).head

      Then("the deployment gets created")
      WaitTestSupport.validFor("deployment visible", 1.second)(marathon.listDeploymentsForBaseGroup().value.size == 1)

      When("the deployment is forcefully removed")
      val delete = marathon.deleteDeployment(deploymentId, force = true)
      delete should be(Accepted)

      Then("the deployment should be gone")
      waitForEvent("deployment_failed")
      marathon.listDeploymentsForBaseGroup().value should have size 0

      Then("the app should still be there")
      marathon.app(id) should be(OK)
    }

    "rollback a deployment" in {
      Given("a new app with constraints that cannot be fulfilled")
      val c = Seq("nonExistent", "CLUSTER", "na")
      val id = appId()
      val app = App(id.toString, constraints = Set(c), cmd = Some("na"), instances = 5, portDefinitions = None)

      val create = marathon.createAppV2(app)
      create should be(Created)
      // Created
      val deploymentId = extractDeploymentIds(create).head

      Then("the deployment gets created")
      WaitTestSupport.validFor("deployment visible", 5.second)(marathon.listDeploymentsForBaseGroup().value.size == 1)

      When("the deployment is rolled back")
      val delete = marathon.deleteDeployment(deploymentId, force = false)
      delete should be(OK)

      Then("the deployment should be gone")
      waitForEvent("deployment_failed")
      waitForDeployment(delete)
      WaitTestSupport.waitUntil("Deployments get removed from the queue") {
        marathon.listDeploymentsForBaseGroup().value.isEmpty
      }

      Then("the app should also be gone")
      marathon.app(id) should be(NotFound)
    }

    "Docker info is not automagically created" in {
      Given("An app with MESOS container")
      val id = appId()
      val app = App(
        id = id.toString,
        cmd = Some("sleep 1"),
        instances = 0,
        container = Some(Container(`type` = EngineType.Mesos))
      )

      When("The request is sent")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)

      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)

      When("We fetch the app definition")
      val getResult1 = marathon.app(id)
      val maybeContainer1 = getResult1.value.app.container

      Then("The container should still be of type MESOS")
      maybeContainer1 should not be empty
      maybeContainer1.get.`type` should be(EngineType.Mesos)

      And("container.docker should not be set")
      maybeContainer1.get.docker shouldBe empty

      When("We update the app")
      val update = marathon.updateApp(id, AppUpdate(cmd = Some("sleep 100")))

      Then("The app gets updated")
      update should be(OK)
      waitForDeployment(update)

      When("We fetch the app definition")
      val getResult2 = marathon.app(id)
      val maybeContainer2 = getResult2.value.app.container

      Then("The container should still be of type MESOS")
      maybeContainer2 should not be empty
      maybeContainer2.get.`type` should be(EngineType.Mesos)

      And("container.docker should not be set")
      maybeContainer2.get.docker shouldBe empty
    }

    "create a simple app with a docker container and update it" in {
      Given("a new app")
      val id = appId()

      val app = App(
        id = id.toString,
        cmd = Some("cmd"),
        container = Some(Container(
          `type` = EngineType.Docker,
          docker = Some(DockerContainer(
            image = "jdef/helpme"
          )),
          portMappings = Option(Seq(
            ContainerPortMapping(containerPort = 3000, protocol = NetworkProtocol.Tcp)
          ))
        )),
        instances = 0,
        networks = Seq(Network(mode = NetworkMode.ContainerBridge))
      )

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)

      // change port from 3000 to 4000
      val appUpdate = AppUpdate(container = Some(raml.Container(
        EngineType.Docker,
        docker = Some(raml.DockerContainer(
          image = "jdef/helpme"
        )), portMappings = Option(Seq(
          ContainerPortMapping(containerPort = 4000, protocol = NetworkProtocol.Tcp)
        ))
      )))

      val updateResult = marathon.updateApp(app.id.toPath, appUpdate, force = true)

      And("The app is updated")
      updateResult should be(OK)

      Then("The container is updated correctly")
      val updatedApp = marathon.app(id)
      updatedApp.value.app.container should not be None
      updatedApp.value.app.container.flatMap(_.portMappings).exists(_.nonEmpty) should be(true)
      updatedApp.value.app.container.flatMap(_.portMappings).flatMap(_.headOption.map(_.containerPort)) should contain(4000)
    }
  }

  private val ramlHealthCheck = AppHealthCheck(
    protocol = AppHealthCheckProtocol.Http,
    gracePeriodSeconds = 20,
    intervalSeconds = 1,
    maxConsecutiveFailures = 10,
    portIndex = Some(0),
    delaySeconds = 2
  )
}
