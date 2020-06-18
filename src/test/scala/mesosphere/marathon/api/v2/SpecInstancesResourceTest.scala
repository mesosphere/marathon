package mesosphere.marathon
package api.v2

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import mesosphere.UnitTest
import mesosphere.marathon.api.{JsonTestHelper, TaskKiller, TestAuthFixture}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.{GroupCreation, JerseyTest, SettableClock}
import org.mockito.Matchers
import org.mockito.Mockito._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SpecInstancesResourceTest extends UnitTest with GroupCreation with JerseyTest {

  case class Fixture(
      auth: TestAuthFixture = new TestAuthFixture,
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      taskKiller: TaskKiller = mock[TaskKiller],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager],
      config: MarathonConf = AllConf.withTestConfig("--deprecated_features", "text_plain_tasks"),
      groupManager: GroupManager = mock[GroupManager]
  ) {
    val identity = auth.identity
    val appsTaskResource = new AppTasksResource(
      instanceTracker,
      taskKiller,
      healthCheckManager,
      config,
      groupManager,
      auth.auth,
      auth.auth
    )
  }

  case class FixtureWithRealTaskKiller(
      auth: TestAuthFixture = new TestAuthFixture,
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager],
      config: MarathonConf = AllConf.withTestConfig("--deprecated_features", "text_plain_tasks"),
      groupManager: GroupManager = mock[GroupManager]
  ) {
    val identity = auth.identity
    val killService = mock[KillService]
    implicit val system = ActorSystem("test")
    def materializerSettings = ActorMaterializerSettings(system)
    implicit val mat = ActorMaterializer(materializerSettings)
    val taskKiller = new TaskKiller(instanceTracker, groupManager, auth.auth, auth.auth, killService)
    val appsTaskResource = new AppTasksResource(
      instanceTracker,
      taskKiller,
      healthCheckManager,
      config,
      groupManager,
      auth.auth,
      auth.auth
    )
  }

  "SpecInstancesResource" should {
    "deleteMany" in new Fixture {
      val appId = AbsolutePathId("/my/app")
      val host = "host"
      val clock = new SettableClock()
      val instance1 =
        TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).addTaskStaged().getInstance()
      val instance2 =
        TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).addTaskStaged().getInstance()
      val toKill = Seq(instance1, instance2)

      taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
      groupManager.runSpec(appId) returns Some(AppDefinition(appId, role = "*"))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = asyncRequest { r =>
        appsTaskResource.deleteMany(appId.toString, host, scale = false, force = false, wipe = false, auth.request, r)
      }
      response.getStatus shouldEqual 200

      val expected =
        s"""
           |{ "tasks": [
           |  {
           |    "appId" : "/my/app",
           |    "healthCheckResults" : [ ],
           |    "host" : "host.some",
           |    "id" : "${instance1.appTask.taskId.idString}",
           |    "role" : "*",
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
           |    "role" : "*",
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
        .assertThatJsonString(response.getEntity.toString)
        .correspondsToJsonString(expected)
    }

    "deleteMany with scale and wipe fails" in new Fixture {
      val appId = "/my/app"
      val host = "host"

      val response = asyncRequest { r =>
        appsTaskResource.deleteMany(appId, host, scale = true, force = false, wipe = true, auth.request, r)
      }

      response.getEntity().toString should include("You cannot use scale and wipe at the same time.")
    }

    "deleteMany with wipe delegates to taskKiller with wipe value" in new Fixture {
      val appId = "/my/app"
      val host = "host"
      healthCheckManager.statuses(appId.toAbsolutePath) returns Future.successful(collection.immutable.Map.empty)
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])

      val response = asyncRequest { r =>
        appsTaskResource.deleteMany(appId, host, scale = false, force = false, wipe = true, auth.request, r)
      }
      response.getStatus shouldEqual 200
      verify(taskKiller).kill(any, any, Matchers.eq(true))(any)
    }

    "deleteOne" in new Fixture {
      val clock = new SettableClock()
      val appId = AbsolutePathId("/my/app")
      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val toKill = Seq(instance1)

      instanceTracker.specInstances(appId) returns Future.successful(Seq(instance1, instance2))
      taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
      groupManager.app(appId) returns Some(AppDefinition(appId, role = "*"))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = asyncRequest { r =>
        appsTaskResource.deleteOne(
          appId.toString,
          instance1.instanceId.idString,
          scale = false,
          force = false,
          wipe = false,
          auth.request,
          r
        )
      }
      response.getStatus shouldEqual 200

      val expected =
        s"""
          |{ "task":
          |  {
          |    "appId" : "/my/app",
          |    "healthCheckResults" : [ ],
          |    "host" : "host.some",
          |    "id" : "${instance1.appTask.taskId.idString}",
          |    "role" : "*",
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
        .assertThatJsonString(response.getEntity.toString)
        .correspondsToJsonString(expected)
      verify(taskKiller).kill(equalTo(appId), any, any)(any)
      verifyNoMoreInteractions(taskKiller)
    }

    "deleteOne with scale and wipe fails" in new Fixture {
      val appId = AbsolutePathId("/my/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val id = Task.Id(instanceId)

      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = asyncRequest { r =>
        appsTaskResource.deleteOne(appId.toString, id.toString, scale = true, force = false, wipe = true, auth.request, r)
      }

      response.getStatus should be(400)
      response.getEntity shouldEqual """{"message":"You cannot use scale and wipe at the same time."}"""
    }

    "deleteOne with wipe delegates to taskKiller with wipe value" in new Fixture {
      val clock = new SettableClock()
      val appId = AbsolutePathId("/my/app")
      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, now = clock.now(), version = clock.now()).getInstance()
      val toKill = Seq(instance1)

      instanceTracker.specInstances(appId) returns Future.successful(Seq(instance1, instance2))
      taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
      groupManager.app(appId) returns Some(AppDefinition(appId, role = "*"))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

      val response = asyncRequest { r =>
        appsTaskResource.deleteOne(
          appId.toString,
          instance1.instanceId.idString,
          scale = false,
          force = false,
          wipe = true,
          auth.request,
          r
        )
      }
      response.getStatus shouldEqual 200

      val expected =
        s"""
           |{ "task":
           |  {
           |    "appId" : "/my/app",
           |    "healthCheckResults" : [ ],
           |    "host" : "host.some",
           |    "id" : "${instance1.appTask.taskId.idString}",
           |    "role" : "*",
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
        .assertThatJsonString(response.getEntity.toString)
        .correspondsToJsonString(expected)
      verify(taskKiller).kill(equalTo(appId), any, org.mockito.Matchers.eq(true))(any)
      verifyNoMoreInteractions(taskKiller)
    }

    "get tasks" in new Fixture {
      val clock = new SettableClock()

      val appId = AbsolutePathId("/my/app")

      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, clock.now()).getInstance()

      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(instance1, instance2)))
      healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)
      groupManager.app(appId) returns Some(AppDefinition(appId, role = "*"))

      val response = asyncRequest { r => appsTaskResource.indexJson("/my/app", auth.request, r) }
      response.getStatus shouldEqual 200

      val expected =
        s"""
          |{ "tasks": [
          |  {
          |    "appId" : "/my/app",
          |    "healthCheckResults" : [ ],
          |    "host" : "host.some",
          |    "id" : "${instance1.appTask.taskId.idString}",
          |    "role" : "*",
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
          |    "role" : "*",
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
        .assertThatJsonString(response.getEntity.toString)
        .correspondsToJsonString(expected)
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      groupManager.rootGroup() returns createRootGroup()

      When("the indexJson is fetched")
      val indexJson = asyncRequest { r => appsTaskResource.indexJson("", req, r) }
      Then("we receive a NotAuthenticated response")
      indexJson.getStatus should be(auth.NotAuthenticatedStatus)

      When("the index as txt is fetched")
      val indexTxt = asyncRequest { r => appsTaskResource.indexTxt("", req = req, asyncResponse = r) }
      Then("we receive a NotAuthenticated response")
      indexTxt.getStatus should be(auth.NotAuthenticatedStatus)

      When("One task is deleted")
      val deleteOne = asyncRequest { r =>
        appsTaskResource.deleteOne("appId", "taskId", false, false, false, req, r)
      }
      Then("we receive a NotAuthenticated response")
      deleteOne.getStatus should be(auth.NotAuthenticatedStatus)

      When("multiple tasks are deleted")
      val deleteMany = asyncRequest { r =>
        appsTaskResource.deleteMany("appId", "host", false, false, false, req, r)
      }
      Then("we receive a NotAuthenticated response")
      deleteMany.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access to indexJson without authorization leads to a 404 if the app does not exist" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the app does not exist")
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.app("/app".toAbsolutePath) returns None

      When("the indexJson is fetched")
      val indexJson = asyncRequest { r => appsTaskResource.indexJson("/app", req, r) }
      Then("we receive a 404")
      indexJson.getStatus should be(404)
    }

    "access to indexJson without authorization is not allowed if the app exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the app exists")
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.app("/app".toAbsolutePath) returns Some(AppDefinition("/app".toAbsolutePath, role = "*"))

      When("the indexJson is fetched")
      val indexJson = asyncRequest { r => appsTaskResource.indexJson("/app", req, r) }
      Then("we receive a not authorized response")
      indexJson.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to indexJson without authorization leads to a 404 if the group does not exist" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the group does not exist")
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
      groupManager.group("/group".toAbsolutePath) returns None

      When("the indexJson is fetched")
      val indexJson = asyncRequest { r => appsTaskResource.indexJson("/group/*", req, r) }
      Then("we receive a 404")
      indexJson.getStatus should be(404)
    }

    "access to indexJson without authorization is not allowed if the group exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("the group exists")
      val groupPath = "/group".toAbsolutePath
      groupManager.group(groupPath) returns Some(createGroup(groupPath))
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the indexJson is fetched")
      val indexJson = asyncRequest { r => appsTaskResource.indexJson("/group/*", req, r) }
      Then("we receive a not authorized response")
      indexJson.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to indexTxt without authorization is not allowed if the app exists" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app exists")
      groupManager.app("/app".toAbsolutePath) returns Some(AppDefinition("/app".toAbsolutePath, role = "*"))
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the index as txt is fetched")
      val indexTxt = asyncRequest { r => appsTaskResource.indexTxt("/app", req = req, asyncResponse = r) }
      Then("we receive a not authorized response")
      indexTxt.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to indexTxt without authorization leads to a 404 if the the app does not exist" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app not exists")
      groupManager.app("/app".toAbsolutePath) returns None
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the index as txt is fetched")
      val indexTxt = asyncRequest { r => appsTaskResource.indexTxt("/app", req = req, asyncResponse = r) }
      Then("we receive a not authorized response")
      indexTxt.getStatus should be(404)
    }

    "access to deleteOne without authorization is not allowed if the app exists" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val appId = AbsolutePathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = Task.Id(instanceId)

      Given("The app exists")
      groupManager.runSpec("/app".toAbsolutePath) returns Some(AppDefinition(appId, role = "*"))
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("deleteOne is called")
      val deleteOne = asyncRequest { r =>
        appsTaskResource.deleteOne("app", taskId.toString, false, false, false, req, r)
      }
      Then("we receive a not authorized response")
      deleteOne.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to deleteOne without authorization leads to a 404 if the the app does not exist" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val appId = AbsolutePathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = Task.Id(instanceId)

      Given("The app not exists")
      groupManager.runSpec("/app".toAbsolutePath) returns None
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("deleteOne is called")
      val deleteOne = asyncRequest { r =>
        appsTaskResource.deleteOne("app", taskId.toString, false, false, false, req, r)
      }
      Then("we receive a not authorized response")
      deleteOne.getStatus should be(404)
    }

    "access to deleteMany without authorization is not allowed if the app exists" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app exists")
      groupManager.runSpec("/app".toAbsolutePath) returns Some(AppDefinition("/app".toAbsolutePath, role = "*"))
      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("deleteMany is called")
      val deleteMany = asyncRequest { r =>
        appsTaskResource.deleteMany("app", "host", false, false, false, req, r)
      }
      Then("we receive a not authorized response")
      deleteMany.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to deleteMany without authorization leads to a 404 if the the app does not exist" in new FixtureWithRealTaskKiller() {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      Given("The app not exists")
      groupManager.runSpec("/app".toAbsolutePath) returns None

      When("deleteMany is called")
      val deleteMany = asyncRequest { r =>
        appsTaskResource.deleteMany("app", "host", false, false, false, req, r)
      }
      Then("we receive a not authorized response")
      deleteMany.getStatus should be(404)
    }
  }

}
