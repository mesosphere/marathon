package mesosphere.marathon.integration

import java.util.UUID

import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.integration.facades.{ ITEnrichedTask, ITDeployment, ITQueueItem, MarathonFacade }
import MarathonFacade._
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state._
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.control.NonFatal
import org.apache.mesos.{ Protos => MesosProtos }

class AppDeployIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //clean up state before running the test case
  before(cleanUp())

  test("create a simple app without health checks") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
    waitForTasks(app.id, 1) //make sure, the app has really started
  }

  test("redeploying an app without changes should not cause restarts") {
    Given("an deployed app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    val result = marathon.createAppV2(app)
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
    val taskBeforeRedeployment = waitForTasks(app.id, 1) //make sure, the app has really started

    When("redeploying the app without changes")
    marathon.updateApp(app.id, AppUpdate(id = Some(app.id), cmd = app.cmd))
    waitForEvent("deployment_success")
    val tasksAfterRedeployment = waitForTasks(app.id, 1) //make sure, the app has really started

    Then("no tasks should have been restarted")
    taskBeforeRedeployment should be (tasksAfterRedeployment)
  }

  test("backoff delays are reset on configuration changes") {
    val app: AppDefinition = createAFailingAppResultingInBackOff()

    When("we force deploy a working configuration")
    val deployment2 = marathon.updateApp(app.id, AppUpdate(cmd = Some("sleep 120; true")), force = true)

    Then("The app deployment is created")
    deployment2.code should be (200) //Created

    And("and the app gets deployed immediately")
    waitForEvent("deployment_success")
    waitForTasks(app.id, 1)
  }

  test("backoff delays are NOT reset on scaling changes") {
    val app: AppDefinition = createAFailingAppResultingInBackOff()

    When("we force deploy a scale change")
    val deployment2 = marathon.updateApp(app.id, AppUpdate(instances = Some(3)), force = true)

    Then("The app deployment is created")
    deployment2.code should be (200) //Created

    And("BUT our app still has a backoff delay")
    val queueAfterScaling: List[ITQueueItem] = marathon.launchQueue().value.queue
    queueAfterScaling should have size 1
    queueAfterScaling.map(_.delay.overdue) should contain(false)
  }

  test("restarting an app with backoff delay starts immediately") {
    val app: AppDefinition = createAFailingAppResultingInBackOff()

    When("we force a restart")
    val deployment2 = marathon.restartApp(app.id, force = true)

    Then("The app deployment is created")
    deployment2.code should be (200) //Created

    And("the task eventually fails AGAIN")
    val events2 = waitForEvents("status_update_event", "status_update_event")()
    val statuses2 = events2.values.flatMap(_.map(_.info("taskStatus")))
    statuses2 should contain("TASK_RUNNING")
    statuses2 should contain("TASK_FAILED")
  }

  private[this] def createAFailingAppResultingInBackOff(): AppDefinition = {
    Given("a new app")
    val app =
      appProxy(testBasePath / s"app${UUID.randomUUID()}", "v1", instances = 1, withHealth = false)
        .copy(
          cmd = Some("false"),
          backoff = 1.hour,
          maxLaunchDelay = 1.hour
        )

    When("we request to deploy the app")
    val result = marathon.createAppV2(app)

    Then("The app deployment is created")
    result.code should be(201) //Created

    And("the task eventually fails")
    val events = waitForEvents("status_update_event", "status_update_event")()
    val statuses = events.values.flatMap(_.map(_.info("taskStatus")))
    statuses should contain("TASK_RUNNING")
    statuses should contain("TASK_FAILED")

    And("our app gets a backoff delay")
    WaitTestSupport.waitUntil("queue item", 10.seconds) {
      try {
        val queue: List[ITQueueItem] = marathon.launchQueue().value.queue
        queue should have size 1
        queue.map(_.delay.overdue) should contain(false)
        true
      }
      catch {
        case NonFatal(e) =>
          log.info("while querying queue", e)
          false
      }
    }
    app
  }

  test("increase the app count metric when an app is created") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)

    var appCount = (marathon.metrics().entityJson \ "gauges" \ "service.mesosphere.marathon.app.count" \ "value").as[Int]
    appCount should be (0)

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app count metric should increase")
    result.code should be (201) // Created
    appCount = (marathon.metrics().entityJson \ "gauges" \ "service.mesosphere.marathon.app.count" \ "value").as[Int]
    appCount should be (1)
  }

  test("create a simple app without health checks via secondary (proxying)") {
    if (!config.useExternalSetup) {
      Given("a new app")
      val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)

      When("The app is deployed")
      val result = marathonProxy.createAppV2(app)

      Then("The app is created")
      result.code should be (201) //Created
      extractDeploymentIds(result) should have size 1
      waitForEvent("deployment_success")
      waitForTasks(app.id, 1) //make sure, the app has really started
    }
  }

  test("create a simple app with http health checks") {
    Given("a new app")
    val app = appProxy(testBasePath / "http-app", "v1", instances = 1, withHealth = false).
      copy(healthChecks = Set(healthCheck))
    val check = appProxyCheck(app.id, "v1", true)

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
    check.pingSince(5.seconds) should be (true) //make sure, the app has really started
  }

  test("create a simple app with http health checks using port instead of portIndex") {
    Given("a new app")
    val app = appProxy(testBasePath / "http-app", "v1", instances = 1, withHealth = false).
      copy(
        portDefinitions = PortDefinitions(31000),
        requirePorts = true,
        healthChecks = Set(healthCheck.copy(port = Some(31000)))
      )
    val check = appProxyCheck(app.id, "v1", true)

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
    check.pingSince(5.seconds) should be (true) //make sure, the app has really started
  }

  test("create a simple app with tcp health checks") {
    Given("a new app")
    val app = appProxy(testBasePath / "tcp-app", "v1", instances = 1, withHealth = false).
      copy(healthChecks = Set(healthCheck.copy(protocol = Protocol.TCP)))

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
  }

  test("create a simple app with command health checks") {
    Given("a new app")
    val app = appProxy(testBasePath / "command-app", "v1", instances = 1, withHealth = false).
      copy(healthChecks = Set(healthCheck.copy(protocol = Protocol.COMMAND, command = Some(Command("true")))))

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")
  }

  test("list running apps and tasks") {
    Given("a new app is deployed")
    val appId = testBasePath / "app"
    val app = appProxy(appId, "v1", instances = 2, withHealth = false)
    marathon.createAppV2(app).code should be (201) //Created

    When("the deployment has finished")
    waitForEvent("deployment_success")

    Then("the list of running app tasks can be fetched")
    val apps = marathon.listAppsInBaseGroup
    apps.code should be(200)
    apps.value should have size 1

    val tasksResult: RestResult[List[ITEnrichedTask]] = marathon.tasks(appId)
    tasksResult.code should be(200)

    val tasks = tasksResult.value
    tasks should have size 2
    tasks.foreach(_.ipAddresses.get should not be empty)
  }

  test("an unhealthy app fails to deploy") {
    Given("a new app that is not healthy")
    val appId = testBasePath / "failing"
    val check = appProxyCheck(appId, "v1", state = false)
    val app = appProxy(appId, "v1", instances = 1, withHealth = true)

    When("The app is deployed")
    val create = marathon.createAppV2(app)

    Then("We receive a deployment created confirmation")
    create.code should be (201) //Created
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
    val delete = marathon.deleteApp(appId, force = true)
    delete.code should be (200)
    waitForChange(delete)
    marathon.listAppsInBaseGroup.value should have size 0
  }

  test("update an app") {
    Given("a new app")
    val appId = testBasePath / "app"
    val v1 = appProxy(appId, "v1", instances = 1, withHealth = true)
    marathon.createAppV2(v1).code should be (201)
    waitForEvent("deployment_success")
    val before = marathon.tasks(appId)

    When("The app is updated")
    val check = appProxyCheck(appId, "v2", state = true)
    val update = marathon.updateApp(v1.id, AppUpdate(cmd = appProxy(appId, "v2", 1).cmd))

    Then("The app gets updated")
    update.code should be (200)
    waitForEvent("deployment_success")
    waitForTasks(appId, before.value.size)
    check.pingSince(5.seconds) should be (true) //make sure, the new version is alive
  }

  test("scale an app up and down") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app).code should be (201)
    waitForEvent("deployment_success")

    When("The app gets an update to be scaled up")
    val scaleUp = marathon.updateApp(app.id, AppUpdate(instances = Some(2)))

    Then("New tasks are launched")
    scaleUp.code should be (200) //OK
    waitForEvent("deployment_success")
    waitForTasks(app.id, 2)

    When("The app gets an update to be scaled down")
    val scaleDown = marathon.updateApp(app.id, AppUpdate(instances = Some(1)))

    Then("Tasks are killed")
    scaleDown.code should be (200) //OK
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")
    waitForTasks(app.id, 1)
  }

  test("restart an app") {
    Given("a new app")
    val appId = testBasePath / "app"
    val v1 = appProxy(appId, "v1", instances = 1, withHealth = false)
    marathon.createAppV2(v1).code should be (201)
    waitForEvent("deployment_success")
    val before = marathon.tasks(appId)

    When("The app is restarted")
    val restart = marathon.restartApp(v1.id)

    Then("All instances of the app get restarted")
    restart.code should be (200)
    waitForChange(restart)
    val after = marathon.tasks(appId)
    waitForTasks(appId, before.value.size)
    before.value.toSet should not be after.value.toSet
  }

  test("list app versions") {
    Given("a new app")
    val v1 = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    val createResponse = marathon.createAppV2(v1)
    createResponse.code should be (201)
    waitForEvent("deployment_success")

    When("The list of versions is fetched")
    val list = marathon.listAppVersions(v1.id)

    Then("The response should contain all the versions")
    list.code should be (200)
    list.value.versions should have size 1
    list.value.versions.head should be (createResponse.value.version)
  }

  test("correctly version apps") {
    Given("a new app")
    val v1 = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    val createResponse = marathon.createAppV2(v1)
    createResponse.code should be (201)
    val originalVersion = createResponse.value.version
    waitForEvent("deployment_success")

    When("A resource specification is updated")
    val updatedDisk: Double = v1.disk + 1.0
    val appUpdate = AppUpdate(Option(v1.id), disk = Option(updatedDisk))
    val updateResponse = marathon.updateApp(v1.id, appUpdate)
    updateResponse.code should be (200)
    waitForEvent("deployment_success")

    Then("It should create a new version with the right data")
    val responseOriginalVersion = marathon.appVersion(v1.id, originalVersion)
    responseOriginalVersion.code should be (200)
    responseOriginalVersion.value.disk should be (v1.disk)

    val updatedVersion = updateResponse.value.version
    val responseUpdatedVersion = marathon.appVersion(v1.id, updatedVersion)
    responseUpdatedVersion.code should be (200)
    responseUpdatedVersion.value.disk should be (updatedDisk)
  }

  test("kill a task of an App") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app).code should be (201)
    waitForEvent("deployment_success")
    val taskId = marathon.tasks(app.id).value.head.id

    When("a task of an app is killed")
    marathon.killTask(app.id, taskId).code should be (200)

    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")

    Then("All instances of the app get restarted")
    waitForTasks(app.id, 1)
    marathon.tasks(app.id).value.head should not be taskId
  }

  test("kill a task of an App with scaling") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 2, withHealth = false)
    marathon.createAppV2(app).code should be (201)
    waitForEvent("deployment_success")
    val taskId = marathon.tasks(app.id).value.head.id

    When("a task of an app is killed and scaled")
    marathon.killTask(app.id, taskId, scale = true).code should be (200)
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")

    Then("All instances of the app get restarted")
    waitForTasks(app.id, 1)
    marathon.app(app.id).value.app.instances should be (1)
  }

  test("kill all tasks of an App") {
    Given("a new app with multiple tasks")
    val app = appProxy(testBasePath / "app", "v1", instances = 2, withHealth = false)
    marathon.createAppV2(app).code should be (201)
    waitForEvent("deployment_success")

    When("all task of an app are killed")
    marathon.killAllTasks(app.id).code should be (200)
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_KILLED")

    Then("All instances of the app get restarted")
    waitForTasks(app.id, 2)
  }

  test("kill all tasks of an App with scaling") {
    Given("a new app with multiple tasks")
    val app = appProxy(testBasePath / "tokill", "v1", instances = 2, withHealth = false)
    marathon.createAppV2(app).code should be (201)
    waitForEvent("deployment_success")
    marathon.app(app.id).value.app.instances should be (2)

    When("all task of an app are killed")
    val result = marathon.killAllTasksAndScale(app.id)
    result.code should be (200)
    result.value.version should not be empty

    Then("All instances of the app get restarted")
    waitForEvent("deployment_success")
    waitForTasks(app.id, 0)
    marathon.app(app.id).value.app.instances should be (0)
  }

  test("delete an application") {
    Given("a new app with one task")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app).code should be (201)
    waitForEvent("deployment_success")

    When("the app is deleted")
    val delete = marathon.deleteApp(app.id)
    delete.code should be (200)
    waitForChange(delete)

    Then("All instances of the app get restarted")
    marathon.listAppsInBaseGroup.value should have size 0
  }

  test("create and deploy an app with two tasks") {
    Given("a new app")
    log.info("new app")
    val appIdPath: PathId = testBasePath / "/test/app"
    val appId: String = appIdPath.toString
    val app = appProxy(appIdPath, "v1", instances = 2, withHealth = false)

    When("the app gets posted")
    log.info("new app")
    val createdApp: RestResult[AppDefinition] = marathon.createAppV2(app)

    Then("the app is created and a success event arrives eventually")
    log.info("new app")
    createdApp.code should be(201) // created

    Then("we get various events until deployment success")
    val deploymentIds: Seq[String] = extractDeploymentIds(createdApp)
    deploymentIds.length should be(1)
    val deploymentId = deploymentIds.head

    log.info("waiting for deployment success")
    val events: Map[String, Seq[CallbackEvent]] = waitForEvents(
      "api_post_event", "group_change_success", "deployment_info",
      "status_update_event", "status_update_event",
      "deployment_success")(30.seconds)

    val Seq(apiPostEvent) = events("api_post_event")
    apiPostEvent.info("appDefinition").asInstanceOf[Map[String, Any]]("id").asInstanceOf[String] should
      be(appId)

    val Seq(groupChangeSuccess) = events("group_change_success")
    groupChangeSuccess.info("groupId").asInstanceOf[String] should be(appIdPath.parent.toString)

    val Seq(taskUpdate1, taskUpdate2) = events("status_update_event")
    taskUpdate1.info("appId").asInstanceOf[String] should be(appId)
    taskUpdate2.info("appId").asInstanceOf[String] should be(appId)

    val Seq(deploymentSuccess) = events("deployment_success")
    deploymentSuccess.info("id") should be(deploymentId)

    Then("after that deployments should be empty")
    val event: RestResult[List[ITDeployment]] = marathon.listDeploymentsForBaseGroup()
    event.value should be('empty)

    Then("Both tasks respond to http requests")
    def pingTask(taskInfo: CallbackEvent): RestResult[String] = {
      val host: String = taskInfo.info("host").asInstanceOf[String]
      val port: Int = taskInfo.info("ports").asInstanceOf[Seq[Int]].head
      appMock.ping(host, port)
    }

    pingTask(taskUpdate1).entityString should be(s"Pong $appId\n")
    pingTask(taskUpdate2).entityString should be(s"Pong $appId\n")
  }

  test("stop (forcefully delete) a deployment") {
    Given("a new app with constraints that cannot be fulfilled")
    val c = Protos.Constraint.newBuilder().setField("nonExistent").setOperator(Operator.CLUSTER).setValue("na").build()
    val appId = testBasePath / "app"
    val app = AppDefinition(appId, constraints = Set(c), cmd = Some("na"), instances = 5, portDefinitions = List.empty)

    val create = marathon.createAppV2(app)
    create.code should be (201) // Created
    val deploymentId = extractDeploymentIds(create).head

    Then("the deployment gets created")
    WaitTestSupport.validFor("deployment visible", 1.second)(marathon.listDeploymentsForBaseGroup().value.size == 1)

    When("the deployment is forcefully removed")
    val delete = marathon.deleteDeployment(deploymentId, force = true)
    delete.code should be (202)

    Then("the deployment should be gone")
    waitForEvent("deployment_failed")
    marathon.listDeploymentsForBaseGroup().value should have size 0

    Then("the app should still be there")
    marathon.app(appId).code should be (200)
  }

  test("rollback a deployment") {
    Given("a new app with constraints that cannot be fulfilled")
    val c = Protos.Constraint.newBuilder().setField("nonExistent").setOperator(Operator.CLUSTER).setValue("na").build()
    val appId = testBasePath / "app"
    val app = AppDefinition(appId, constraints = Set(c), cmd = Some("na"), instances = 5, portDefinitions = List.empty)

    val create = marathon.createAppV2(app)
    create.code should be (201) // Created
    val deploymentId = extractDeploymentIds(create).head

    Then("the deployment gets created")
    WaitTestSupport.validFor("deployment visible", 1.second)(marathon.listDeploymentsForBaseGroup().value.size == 1)

    When("the deployment is rolled back")
    val delete = marathon.deleteDeployment(deploymentId, force = false)
    delete.code should be (200)

    Then("the deployment should be gone")
    waitForEvent("deployment_failed")
    waitForEvent("deployment_success")
    WaitTestSupport.waitUntil("Deployments get removed from the queue", 30.seconds) {
      marathon.listDeploymentsForBaseGroup().value.isEmpty
    }

    Then("the app should also be gone")
    marathon.app(appId).code should be (404)
  }

  test("Docker info is not automagically created") {
    Given("An app with MESOS container")
    val appId = testBasePath / "app"
    val app = AppDefinition(
      id = appId,
      cmd = Some("sleep 1"),
      instances = 0,
      container = Some(Container(
        `type` = MesosProtos.ContainerInfo.Type.MESOS
      ))
    )

    app.container should not be empty
    app.container.get.`type` should equal(MesosProtos.ContainerInfo.Type.MESOS)

    When("The request is sent")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created

    extractDeploymentIds(result) should have size 1
    waitForEvent("deployment_success")

    When("We fetch the app definition")
    val getResult1 = marathon.app(appId)
    val maybeContainer1 = getResult1.value.app.container

    Then("The container should still be of type MESOS")
    maybeContainer1 should not be empty
    maybeContainer1.get.`type` should equal(MesosProtos.ContainerInfo.Type.MESOS)

    And("container.docker should not be set")
    maybeContainer1.get.docker shouldBe (empty)

    When("We update the app")
    val update = marathon.updateApp(appId, AppUpdate(cmd = Some("sleep 100")))

    Then("The app gets updated")
    update.code should be (200)
    waitForEvent("deployment_success")

    When("We fetch the app definition")
    val getResult2 = marathon.app(appId)
    val maybeContainer2 = getResult2.value.app.container

    Then("The container should still be of type MESOS")
    maybeContainer2 should not be empty
    maybeContainer2.get.`type` should equal(MesosProtos.ContainerInfo.Type.MESOS)

    And("container.docker should not be set")
    maybeContainer1.get.docker shouldBe (empty)
  }

  def healthCheck = HealthCheck(gracePeriod = 20.second, interval = 1.second, maxConsecutiveFailures = 10)
}
