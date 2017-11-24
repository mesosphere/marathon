package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.async.ExecutionContexts.global
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceStateOpProcessor }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ PathId, _ }
import mesosphere.marathon.test.{ GroupCreation, SettableClock }
import org.mockito.Matchers
import org.mockito.Mockito._
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.duration._

class SpecInstancesResourceTest extends UnitTest with GroupCreation {

  case class Fixture(
      auth: TestAuthFixture = new TestAuthFixture,
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      taskTracker: InstanceTracker = mock[InstanceTracker],
      stateOpProcessor: InstanceStateOpProcessor = mock[InstanceStateOpProcessor],
      taskKiller: TaskKiller = mock[TaskKiller],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager],
      config: MarathonConf = mock[MarathonConf],
      groupManager: GroupManager = mock[GroupManager]) {
    val identity = auth.identity
    val appsTaskResource = new AppTasksResource(
      taskTracker,
      taskKiller,
      healthCheckManager,
      config,
      groupManager,
      auth.auth,
      auth.auth
    )

    config.zkTimeoutDuration returns 1.second
  }

  case class FixtureWithRealTaskKiller(
      auth: TestAuthFixture = new TestAuthFixture,
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      taskTracker: InstanceTracker = mock[InstanceTracker],
      stateOpProcessor: InstanceStateOpProcessor = mock[InstanceStateOpProcessor],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager],
      config: MarathonConf = mock[MarathonConf],
      groupManager: GroupManager = mock[GroupManager]) {
    val identity = auth.identity
    val killService = mock[KillService]
    val taskKiller = new TaskKiller(
      taskTracker, stateOpProcessor, groupManager, service, config, auth.auth, auth.auth, killService)
    val appsTaskResource = new AppTasksResource(
      taskTracker,
      taskKiller,
      healthCheckManager,
      config,
      groupManager,
      auth.auth,
      auth.auth
    )

    config.zkTimeoutDuration returns 1.second
  }

  "SpecInstancesResource" should {
    "deleteMany" in new Fixture {
      val appId = "/my/app".toRootPath
      val host = "host"
      val clock = new SettableClock()
      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).addTaskStaged().getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).addTaskStaged().getInstance()
      val toKill = Seq(instance1, instance2)

      config.zkTimeoutDuration returns 5.seconds
      taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
      groupManager.runSpec(appId) returns Some(AppDefinition(appId))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = appsTaskResource.deleteMany(appId.toString, host, scale = false, force = false, wipe = false, auth.request)
      response.getStatus shouldEqual 200

      val expected =
        s"""
           |{ "tasks": [
           |  {
           |    "appId" : "/my/app",
           |    "healthCheckResults" : [ ],
           |    "host" : "host.some",
           |    "id" : "${instance1.appTask.taskId.idString}",
           |    "ipAddresses" : [ ],
           |    "ports" : [ ],
           |    "servicePorts" : [ ],
           |    "slaveId" : "agent-1",
           |    "state" : "TASK_STAGING",
           |    "stagedAt" : "2015-04-09T12:30:00.000Z",
           |    "version" : "2015-04-09T12:30:00.000Z",
           |    "localVolumes" : [ ]
           |  }, {
           |    "appId" : "/my/app",
           |    "healthCheckResults" : [ ],
           |    "host" : "host.some",
           |    "id" : "${instance2.appTask.taskId.idString}",
           |    "ipAddresses" : [ ],
           |    "ports" : [ ],
           |    "servicePorts" : [ ],
           |    "slaveId" : "agent-1",
           |    "state" : "TASK_STAGING",
           |    "stagedAt" : "2015-04-09T12:30:00.000Z",
           |    "version" : "2015-04-09T12:30:00.000Z",
           |    "localVolumes" : [ ]
           |  } ]
           |}
        """.stripMargin
      JsonTestHelper
        .assertThatJsonString(response.getEntity.asInstanceOf[String])
        .correspondsToJsonString(expected)
    }

    "deleteMany with scale and wipe fails" in new Fixture {
      val appId = "/my/app"
      val host = "host"

      val exception = intercept[BadRequestException] {
        appsTaskResource.deleteMany(appId, host, scale = true, force = false, wipe = true, auth.request)
      }
      exception.getMessage shouldEqual "You cannot use scale and wipe at the same time."
    }

    "deleteMany with wipe delegates to taskKiller with wipe value" in new Fixture {
      val appId = "/my/app"
      val host = "host"
      healthCheckManager.statuses(appId.toRootPath) returns Future.successful(collection.immutable.Map.empty)
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])

      val response = appsTaskResource.deleteMany(appId, host, scale = false, force = false, wipe = true, auth.request)
      response.getStatus shouldEqual 200
      verify(taskKiller).kill(any, any, Matchers.eq(true))(any)
    }

    "deleteOne" in new Fixture {
      val clock = new SettableClock()
      val appId = PathId("/my/app")
      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val toKill = Seq(instance1)

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.specInstances(appId) returns Future.successful(Seq(instance1, instance2))
      taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
      groupManager.app(appId) returns Some(AppDefinition(appId))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = appsTaskResource.deleteOne(
        appId.toString, instance1.instanceId.idString, scale = false, force = false, wipe = false, auth.request
      )
      response.getStatus shouldEqual 200

      val expected =
        s"""
          |{ "task":
          |  {
          |    "appId" : "/my/app",
          |    "healthCheckResults" : [ ],
          |    "host" : "host.some",
          |    "id" : "${instance1.appTask.taskId.idString}",
          |    "ipAddresses" : [ ],
          |    "ports" : [ ],
          |    "servicePorts" : [ ],
          |    "slaveId" : "agent-1",
          |    "state" : "TASK_STAGING",
          |    "stagedAt" : "2015-04-09T12:30:00.000Z",
          |    "version" : "2015-04-09T12:30:00.000Z",
          |    "localVolumes" : [ ]
          |  }
          |}""".stripMargin
      JsonTestHelper
        .assertThatJsonString(response.getEntity.asInstanceOf[String])
        .correspondsToJsonString(expected)
      verify(taskKiller).kill(equalTo(appId), any, any)(any)
      verifyNoMoreInteractions(taskKiller)
    }

    "deleteOne with scale and wipe fails" in new Fixture {
      val appId = PathId("/my/app")
      val id = Task.Id.forRunSpec(appId)

      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val exception = intercept[BadRequestException] {
        appsTaskResource.deleteOne(appId.toString, id.toString, scale = true, force = false, wipe = true, auth.request)
      }
      exception.getMessage shouldEqual "You cannot use scale and wipe at the same time."
    }

    "deleteOne with wipe delegates to taskKiller with wipe value" in new Fixture {
      val clock = new SettableClock()
      val appId = PathId("/my/app")
      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val toKill = Seq(instance1)

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.specInstances(appId) returns Future.successful(Seq(instance1, instance2))
      taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
      groupManager.app(appId) returns Some(AppDefinition(appId))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = appsTaskResource.deleteOne(
        appId.toString, instance1.instanceId.idString, scale = false, force = false, wipe = true, auth.request
      )
      response.getStatus shouldEqual 200

      val expected =
        s"""
           |{ "task":
           |  {
           |    "appId" : "/my/app",
           |    "healthCheckResults" : [ ],
           |    "host" : "host.some",
           |    "id" : "${instance1.appTask.taskId.idString}",
           |    "ipAddresses" : [ ],
           |    "ports" : [ ],
           |    "servicePorts" : [ ],
           |    "slaveId" : "agent-1",
           |    "state" : "TASK_STAGING",
           |    "stagedAt" : "2015-04-09T12:30:00.000Z",
           |    "version" : "2015-04-09T12:30:00.000Z",
           |    "localVolumes" : [ ]
           |  }
           |}""".stripMargin
      JsonTestHelper
        .assertThatJsonString(response.getEntity.asInstanceOf[String])
        .correspondsToJsonString(expected)
      verify(taskKiller).kill(equalTo(appId), any, org.mockito.Matchers.eq(true))(any)
      verifyNoMoreInteractions(taskKiller)
    }

    "get tasks" in new Fixture {
      val clock = new SettableClock()

      val appId = PathId("/my/app")

      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, clock.now()).getInstance()

      config.zkTimeoutDuration returns 5.seconds
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(appId, Seq(instance1, instance2))))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)
      groupManager.app(appId) returns Some(AppDefinition(appId))

      val response = appsTaskResource.indexJson("/my/app", auth.request)
      response.getStatus shouldEqual 200

      val expected =
        s"""
          |{ "tasks": [
          |  {
          |    "appId" : "/my/app",
          |    "healthCheckResults" : [ ],
          |    "host" : "host.some",
          |    "id" : "${instance1.appTask.taskId.idString}",
          |    "ipAddresses" : [ ],
          |    "ports" : [ ],
          |    "servicePorts" : [ ],
          |    "slaveId" : "agent-1",
          |    "state" : "TASK_STAGING",
          |    "stagedAt" : "2015-04-09T12:30:00.000Z",
          |    "version" : "2015-04-09T12:30:00.000Z",
          |    "localVolumes" : [ ]
          |  }, {
          |    "appId" : "/my/app",
          |    "healthCheckResults" : [ ],
          |    "host" : "host.some",
          |    "id" : "${instance2.appTask.taskId.idString}",
          |    "ipAddresses" : [ ],
          |    "ports" : [ ],
          |    "servicePorts" : [ ],
          |    "slaveId" : "agent-1",
          |    "state" : "TASK_STAGING",
          |    "stagedAt" : "2015-04-09T12:30:00.000Z",
          |    "version" : "2015-04-09T12:30:00.000Z",
          |    "localVolumes" : [ ]
          |  } ]
          |}
        """.stripMargin

      JsonTestHelper
        .assertThatJsonString(response.getEntity.asInstanceOf[String])
        .correspondsToJsonString(expected)
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      groupManager.rootGroup() returns createRootGroup()

      When("the indexJson is fetched")
      val indexJson = appsTaskResource.indexJson("", req)
      Then("we receive a NotAuthenticated response")
      indexJson.getStatus should be(auth.NotAuthenticatedStatus)

      When("the index as txt is fetched")
      val indexTxt = appsTaskResource.indexTxt("", req)
      Then("we receive a NotAuthenticated response")
      indexTxt.getStatus should be(auth.NotAuthenticatedStatus)

      When("One task is deleted")
      val deleteOne = appsTaskResource.deleteOne("appId", "taskId", false, false, false, req)
      Then("we receive a NotAuthenticated response")
      deleteOne.getStatus should be(auth.NotAuthenticatedStatus)

      When("multiple tasks are deleted")
      val deleteMany = appsTaskResource.deleteMany("appId", "host", false, false, false, req)
      Then("we receive a NotAuthenticated response")
      deleteMany.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access to indexJson without authorization leads to a 404 if the app does not exist" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the app does not exist")
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.app("/app".toRootPath) returns None

      When("the indexJson is fetched")
      val indexJson = appsTaskResource.indexJson("/app", req)
      Then("we receive a 404")
      indexJson.getStatus should be(404)
    }

    "access to indexJson without authorization is not allowed if the app exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the app exists")
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.app("/app".toRootPath) returns Some(AppDefinition("/app".toRootPath))

      When("the indexJson is fetched")
      val indexJson = appsTaskResource.indexJson("/app", req)
      Then("we receive a not authorized response")
      indexJson.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to indexJson without authorization leads to a 404 if the group does not exist" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the group does not exist")
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.group("/group".toRootPath) returns None

      When("the indexJson is fetched")
      val indexJson = appsTaskResource.indexJson("/group/*", req)
      Then("we receive a 404")
      indexJson.getStatus should be(404)
    }

    "access to indexJson without authorization is not allowed if the group exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the group exists")
      val groupPath = "/group".toRootPath
      groupManager.group(groupPath) returns Some(createGroup(groupPath))
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the indexJson is fetched")
      val indexJson = appsTaskResource.indexJson("/group/*", req)
      Then("we receive a not authorized response")
      indexJson.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to indexTxt without authorization is not allowed if the app exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app exists")
      groupManager.app("/app".toRootPath) returns Some(AppDefinition("/app".toRootPath))
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the index as txt is fetched")
      val indexTxt = appsTaskResource.indexTxt("/app", req)
      Then("we receive a not authorized response")
      indexTxt.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to indexTxt without authorization leads to a 404 if the the app does not exist" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app not exists")
      groupManager.app("/app".toRootPath) returns None
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the index as txt is fetched")
      val indexTxt = appsTaskResource.indexTxt("/app", req)
      Then("we receive a not authorized response")
      indexTxt.getStatus should be(404)
    }

    "access to deleteOne without authorization is not allowed if the app exists" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val taskId = Task.Id.forRunSpec(PathId("/app"))

      Given("The app exists")
      groupManager.runSpec("/app".toRootPath) returns Some(AppDefinition("/app".toRootPath))
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("deleteOne is called")
      val deleteOne = appsTaskResource.deleteOne("app", taskId.toString, false, false, false, req)
      Then("we receive a not authorized response")
      deleteOne.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to deleteOne without authorization leads to a 404 if the the app does not exist" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val taskId = Task.Id.forRunSpec(PathId("/app"))

      Given("The app not exists")
      groupManager.runSpec("/app".toRootPath) returns None
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("deleteOne is called")
      val deleteOne = appsTaskResource.deleteOne("app", taskId.toString, false, false, false, req)
      Then("we receive a not authorized response")
      deleteOne.getStatus should be(404)
    }

    "access to deleteMany without authorization is not allowed if the app exists" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app exists")
      groupManager.runSpec("/app".toRootPath) returns Some(AppDefinition("/app".toRootPath))
      taskTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("deleteMany is called")
      val deleteMany = appsTaskResource.deleteMany("app", "host", false, false, false, req)
      Then("we receive a not authorized response")
      deleteMany.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to deleteMany without authorization leads to a 404 if the the app does not exist" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app not exists")
      groupManager.runSpec("/app".toRootPath) returns None

      When("deleteMany is called")
      val deleteMany = appsTaskResource.deleteMany("app", "host", false, false, false, req)
      Then("we receive a not authorized response")
      deleteMany.getStatus should be(404)
    }
  }

}
