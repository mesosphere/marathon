package mesosphere.marathon
package api.v2

import java.util.Collections

import mesosphere.UnitTest
import mesosphere.marathon.api.{ RestResource, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.async.ExecutionContexts.global
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceStateOpProcessor }
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.mockito.Matchers
import org.mockito.Mockito._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class TasksResourceTest extends UnitTest with GroupCreation {
  case class Fixture(
      auth: TestAuthFixture = new TestAuthFixture,
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      taskTracker: InstanceTracker = mock[InstanceTracker],
      stateOpProcessor: InstanceStateOpProcessor = mock[InstanceStateOpProcessor],
      taskKiller: TaskKiller = mock[TaskKiller],
      config: MarathonConf = mock[MarathonConf],
      groupManager: GroupManager = mock[GroupManager],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager],
      implicit val identity: Identity = mock[Identity]) {
    val killService = mock[KillService]
    val taskResource: TasksResource = new TasksResource(
      taskTracker,
      taskKiller,
      config,
      groupManager,
      healthCheckManager,
      auth.auth,
      auth.auth
    )
  }

  "TasksResource" should {
    "list (txt) tasks with less ports than the current app version" in new Fixture {
      // Regression test for #234
      Given("one app with one task with less ports than required")
      val app = AppDefinition("/foo".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(0)), cmd = Some("sleep"))

      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

      config.zkTimeoutDuration returns 5.seconds

      val tasksByApp = InstanceTracker.InstancesBySpec.forInstances(instance)
      taskTracker.instancesBySpec returns Future.successful(tasksByApp)

      val rootGroup = createRootGroup(apps = Map(app.id -> app))
      groupManager.rootGroup() returns rootGroup

      assert(app.servicePorts.size > instance.appTask.status.networkInfo.hostPorts.size)

      When("Getting the txt tasks index")
      val response = taskResource.indexTxt(auth.request)

      Then("The status should be 200")
      response.getStatus shouldEqual 200
    }

    "list apps when there are no apps" in new Fixture {
      // Regression test for #4932
      Given("no apps")
      config.zkTimeoutDuration returns 5.seconds
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.apps(any) returns Map.empty

      When("Getting the tasks index")
      val response = taskResource.indexJson("status", new java.util.ArrayList[String], auth.request)

      Then("The status should be 200")
      response.getStatus shouldEqual 200
    }

    "killTasks" in new Fixture {
      Given("two apps and 1 task each")
      val app1 = "/my/app-1".toRootPath
      val app2 = "/my/app-2".toRootPath

      val instance1 = TestInstanceBuilder.newBuilder(app1).addTaskStaged().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(app2).addTaskStaged().getInstance()

      val (taskId1, _) = instance1.tasksMap.head
      val (taskId2, _) = instance2.tasksMap.head

      val body = s"""{"ids": ["${taskId1.idString}", "${taskId2.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1, instance2))
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])
      groupManager.app(app1) returns Some(AppDefinition(app1))
      groupManager.app(app2) returns Some(AppDefinition(app2))

      When("we ask to kill both tasks")
      val response = taskResource.killTasks(scale = false, force = false, wipe = false, body = bodyBytes, auth.request)

      Then("The response should be OK")
      response.getStatus shouldEqual 200

      And("The response is the list of killed tasks")
      response.getEntity shouldEqual """{"tasks":[]}"""

      And("Both tasks should be requested to be killed")
      verify(taskKiller).kill(Matchers.eq(app1), any, any)(any)
      verify(taskKiller).kill(Matchers.eq(app2), any, any)(any)

      And("nothing else should be called on the TaskKiller")
      noMoreInteractions(taskKiller)
    }

    "try to kill pod instances" in new Fixture {
      Given("two apps and 1 task each")
      val pod1 = "/pod".toRootPath

      val instance = TestInstanceBuilder.newBuilder(pod1).addTaskRunning(Some("container1")).getInstance()

      val (container, _) = instance.tasksMap.head

      val body = s"""{"ids": ["${container.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance))
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])
      groupManager.app(any) returns None

      When("we ask to kill the pod container")
      val response = taskResource.killTasks(scale = false, force = false, wipe = false, body = bodyBytes, auth.request)

      Then("The response should be OK")
      response.getStatus shouldEqual 200

      And("No task should be called on the TaskKiller")
      noMoreInteractions(taskKiller)
    }

    "killTasks with force" in new Fixture {
      Given("two apps and 1 task each")
      val app1 = "/my/app-1".toRootPath
      val app2 = "/my/app-2".toRootPath

      val instance1 = TestInstanceBuilder.newBuilder(app1).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(app2).addTaskStaged().getInstance()

      val (taskId1, _) = instance1.tasksMap.head
      val (taskId2, _) = instance2.tasksMap.head
      val body = s"""{"ids": ["${taskId1.idString}", "${taskId2.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)
      val deploymentPlan = new DeploymentPlan("plan", createRootGroup(), createRootGroup(), Seq.empty[DeploymentStep], Timestamp.zero)

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1, instance2))
      taskKiller.killAndScale(any, any)(any) returns Future.successful(deploymentPlan)
      groupManager.app(app1) returns Some(AppDefinition(app1))
      groupManager.app(app2) returns Some(AppDefinition(app2))

      When("we ask to kill both tasks")
      val response = taskResource.killTasks(scale = true, force = true, wipe = false, body = bodyBytes, auth.request)

      Then("The response should be OK")
      response.getStatus shouldEqual 200
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("Should create a deployment")
      response.getEntity shouldEqual """{"version":"1970-01-01T00:00:00.000Z","deploymentId":"plan"}"""

      And("app1 and app2 is killed with force")
      verify(taskKiller).killAndScale(Matchers.eq(Map(app1 -> Seq(instance1), app2 -> Seq(instance2))), Matchers.eq(true))(any)

      And("nothing else should be called on the TaskKiller")
      noMoreInteractions(taskKiller)
    }

    "killTasks with scale and wipe fails" in new Fixture {
      Given("a request")
      val app1 = "/my/app-1".toRootPath
      val taskId1 = Task.Id.forRunSpec(app1).idString
      val body = s"""{"ids": ["$taskId1"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      When("we ask to scale AND wipe")
      val exception = intercept[BadRequestException] {
        taskResource.killTasks(scale = true, force = false, wipe = true, body = bodyBytes, auth.request)
      }

      Then("an exception should occur")
      exception.getMessage shouldEqual "You cannot use scale and wipe at the same time."
    }

    "killTasks with wipe delegates to taskKiller with wipe value" in new Fixture {

      Given("a task that shall be killed")
      val app1 = "/my/app-1".toRootPath
      val instance1 = TestInstanceBuilder.newBuilder(app1).addTaskRunning().getInstance()
      val List(taskId1) = instance1.tasksMap.keys.toList
      val body = s"""{"ids": ["${taskId1.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1))
      taskTracker.specInstances(app1) returns Future.successful(Seq(instance1))
      taskKiller.kill(Matchers.eq(app1), any, Matchers.eq(true))(any) returns Future.successful(List(instance1))
      groupManager.app(app1) returns Some(AppDefinition(app1))

      When("we send the request")
      val response = taskResource.killTasks(scale = false, force = false, wipe = true, body = bodyBytes, auth.request)

      Then("The response should be OK")
      response.getStatus shouldEqual 200

      And("the taskKiller receives the wipe flag")
      verify(taskKiller).kill(Matchers.eq(app1), any, Matchers.eq(true))(any)

      And("nothing else should be called on the TaskKiller")
      noMoreInteractions(taskKiller)
    }

    "killTask without authentication is denied when the affected app exists" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val appId = "/my/app".toRootPath
      val taskId1 = Task.Id.forRunSpec(appId).idString
      val taskId2 = Task.Id.forRunSpec(appId).idString
      val taskId3 = Task.Id.forRunSpec(appId).idString
      val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

      Given("the app exists")
      groupManager.app(appId) returns Some(AppDefinition(appId))

      When("kill task is called")
      val killTasks = taskResource.killTasks(scale = true, force = false, wipe = false, body, req)
      Then("we receive a NotAuthenticated response")
      killTasks.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "killTask without authentication is not allowed when the affected app does not exist" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val appId = "/my/app".toRootPath
      val taskId1 = Task.Id.forRunSpec(appId).idString
      val taskId2 = Task.Id.forRunSpec(appId).idString
      val taskId3 = Task.Id.forRunSpec(appId).idString
      val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

      Given("the app does not exist")
      groupManager.app(appId) returns None

      When("kill task is called")
      val killTasks = taskResource.killTasks(scale = true, force = false, wipe = false, body, req)
      Then("we receive a NotAuthenticated response")
      killTasks.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "indexTxt and IndexJson without authentication aren't allowed" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request

      When("the index as json is fetched")
      val running = taskResource.indexJson("status", Collections.emptyList(), req)
      Then("we receive a NotAuthenticated response")
      running.getStatus should be(auth.NotAuthenticatedStatus)

      When("one index as txt is fetched")
      val cancel = taskResource.indexTxt(req)
      Then("we receive a NotAuthenticated response")
      cancel.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied if the affected app exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val appId = "/my/app".toRootPath
      val taskId1 = Task.Id.forRunSpec(appId).idString
      val taskId2 = Task.Id.forRunSpec(appId).idString
      val taskId3 = Task.Id.forRunSpec(appId).idString
      val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

      override val taskKiller = new TaskKiller(
        taskTracker, stateOpProcessor, groupManager, service, config, auth.auth, auth.auth, killService)
      override val taskResource = new TasksResource(
        taskTracker,
        taskKiller,
        config,
        groupManager,
        healthCheckManager,
        auth.auth,
        auth.auth
      )

      Given("the app exists")
      config.zkTimeoutDuration returns 5.seconds
      groupManager.app(appId) returns Some(AppDefinition(appId))
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("kill task is called")
      val killTasks = taskResource.killTasks(scale = false, force = false, wipe = false, body, req)
      Then("we receive a not authorized response")
      killTasks.getStatus should be(auth.UnauthorizedStatus)
    }

    "killTasks fails for invalid taskId" in new Fixture {
      Given("a valid and an invalid taskId")
      val app1 = "/my/app-1".toRootPath
      val taskId1 = Task.Id.forRunSpec(app1).idString
      val body = s"""{"ids": ["$taskId1", "invalidTaskId"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      When("we ask to kill those two tasks")
      val ex = intercept[BadRequestException] {
        taskResource.killTasks(scale = false, force = false, wipe = false, body = bodyBytes, auth.request)
      }

      Then("An exception should be thrown that points to the invalid taskId")
      ex.getMessage should include ("invalidTaskId")

      And("the taskKiller should not be called at all")
      verifyNoMoreInteractions(taskKiller)
    }
  }
}
