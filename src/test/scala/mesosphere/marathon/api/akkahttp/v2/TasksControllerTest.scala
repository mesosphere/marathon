package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.http.scaladsl.model.Uri.{ Path, Query }
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ MediaTypes, StatusCodes, Uri, _ }
import akka.http.scaladsl.server.MalformedQueryParamRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.akkahttp.AuthDirectives.NotAuthorized
import mesosphere.marathon.api.akkahttp.Headers
import mesosphere.marathon.api.akkahttp.Rejections.BadRequest
import mesosphere.marathon.api.{ JsonTestHelper, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PortDefinition, Timestamp }
import mesosphere.marathon.test.{ GroupCreation, SettableClock }
import org.apache.mesos
import org.mockito.Matchers
import org.mockito.Mockito.verifyNoMoreInteractions
import org.scalatest.Inside

import scala.collection.immutable.Seq
import scala.concurrent.Future

class TasksControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging with GroupCreation {
  "TasksController" should {

    "List tasks as json" should {

      "list tasks" in new Fixture {
        Given("some tasks in progress")
        val clock = new SettableClock()
        val app = AppDefinition(id = "/app".toPath, instances = 1)
        val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id, clock.now()).getInstance()
        val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id, clock.now()).getInstance()

        instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("/app".toPath, Seq(instance1, instance2))))
        groupManager.apps(any) returns Map(app.id -> Some(app))
        healthCheckManager.statuses(any) returns Future.successful(Map.empty)

        When("Getting the tasks list")
        Get(Uri./) ~> controller.route ~> check {
          Then("All tasks should be serialized into response")
          status should be(StatusCodes.OK)

          val expected = s"""{
                            |  "tasks" : [ {
                            |    "appId" : "/app",
                            |    "healthCheckResults" : [ ],
                            |    "host" : "${instance1.hostname}",
                            |    "ipAddresses" : [ ],
                            |    "id" : "${instance1.appTask.taskId.idString}",
                            |    "ports" : [ ],
                            |    "servicePorts" : [ ],
                            |    "slaveId" : "agent-1",
                            |    "state" : "TASK_STAGING",
                            |    "stagedAt" : "2015-04-09T12:30:00.000Z",
                            |    "version" : "2015-04-09T12:30:00.000Z",
                            |    "localVolumes" : [ ]
                            |  }, {
                            |    "appId" : "/app",
                            |    "healthCheckResults" : [ ],
                            |    "host" : "${instance2.hostname}",
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
                            |}""".stripMargin
          JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
        }
      }

      "apply status filter" in new Fixture {
        Given("some tasks in progress")
        val clock = new SettableClock()
        val app = AppDefinition(id = "/app".toPath, instances = 1)
        val (runningInstance, runningTask) = runningInstanceAndItsTask(app, clock)
        val (stagingInstance, stagingTask) = stagingInstanceAndItsTask(app, clock)

        instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("/app".toPath, Seq(runningInstance, stagingInstance))))
        groupManager.apps(any) returns Map(app.id -> Some(app))
        healthCheckManager.statuses(any) returns Future.successful(Map.empty)

        When("Getting the tasks list only with status staging")
        Get(Uri./.withQuery(Query("status" -> "staging"))) ~> controller.route ~> check {
          Then("Only one task should be serialized into response")
          status should be(StatusCodes.OK)

          responseAs[String] should include (stagingTask.taskId.idString)
          responseAs[String] should not include (runningTask.taskId.idString)
        }
      }

      "apply multiple status filters" in new Fixture {
        Given("some tasks in progress")
        val clock = new SettableClock()
        val app = AppDefinition(id = "/app".toPath, instances = 1)
        val (runningInstance, runningTask) = runningInstanceAndItsTask(app, clock)
        val (stagingInstance, stagingTask) = stagingInstanceAndItsTask(app, clock)

        instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("/app".toPath, Seq(stagingInstance, runningInstance))))
        groupManager.apps(any) returns Map(app.id -> Some(app))
        healthCheckManager.statuses(any) returns Future.successful(Map.empty)

        When("Getting the tasks list for running as well as staging")
        Get(Uri./.withQuery(Query("status[]" -> "staging", "status[]" -> "running"))) ~> controller.route ~> check {
          Then("All tasks should be serialized into response")
          status should be(StatusCodes.OK)

          responseAs[String] should include (stagingTask.taskId.idString)
          responseAs[String] should include (runningTask.taskId.idString)
        }
      }

      "return http 200 when there are no apps" in new Fixture {
        // Regression test for #4932
        Given("no apps")
        instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.empty)
        groupManager.apps(any) returns Map.empty

        When("Getting the tasks list")
        Get(Uri./) ~> controller.route ~> check {
          Then("The status should be 200")
          status should be(StatusCodes.OK)
        }
      }

      {
        val controller = Fixture(authenticated = false).controller
        behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./))
        behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./).addHeader(Accept(MediaTypes.`application/json`)), " using text/json")
      }

      "see tasks only for application to which is authorized to see" in {
        val clock = new SettableClock()
        val authorizedApp = AppDefinition(id = "/app".toPath, instances = 1)
        val notAuthorizedApp = AppDefinition(id = "/app2".toPath, instances = 1)
        val f = Fixture(authFn = resource => {
          info(resource.asInstanceOf[AppDefinition].id.toString)
          resource.asInstanceOf[AppDefinition].id == authorizedApp.id
        })
        val (authorizedInstance, authorizedTask) = f.runningInstanceAndItsTask(authorizedApp, clock)
        val (notAuthorizedInstance, notAuthorizedTask) = f.runningInstanceAndItsTask(notAuthorizedApp, clock)

        f.instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(authorizedApp.id, Seq(authorizedInstance)), InstanceTracker.SpecInstances.forInstances(notAuthorizedApp.id, Seq(notAuthorizedInstance))))
        f.groupManager.apps(any) returns Map(authorizedApp.id -> Some(authorizedApp), notAuthorizedApp.id -> Some(notAuthorizedApp))
        f.healthCheckManager.statuses(any) returns Future.successful(Map.empty)

        When("Getting the tasks list")
        Get(Uri./) ~> f.controller.route ~> check {
          Then("Only tasks we are authorized to see should be serialized into the response")
          status should be(StatusCodes.OK)

          responseAs[String] should include (authorizedTask.taskId.idString)
          responseAs[String] should not include (notAuthorizedTask.taskId.idString)
        }
      }
    }
  }

  "Delete tasks" should {

    "kill tasks" in new Fixture {
      Given("two apps and 1 task each")
      val app1 = "/my/app-1".toRootPath
      val app2 = "/my/app-2".toRootPath

      val instance1 = TestInstanceBuilder.newBuilder(app1).addTaskStaged().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(app2).addTaskStaged().getInstance()

      val (taskId1, _) = instance1.tasksMap.head
      val (taskId2, _) = instance2.tasksMap.head

      val body = s"""{"ids": ["${taskId1.idString}", "${taskId2.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1, instance2))
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])
      groupManager.app(app1) returns Some(AppDefinition(app1))
      groupManager.app(app2) returns Some(AppDefinition(app2))

      When("Killing task")
      Post(Uri./.withPath(Path("/delete")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        Then("Response should be OK")
        status should be(StatusCodes.OK)
        responseAs[String] should be ("""{
                                        |  "tasks" : [ ]
                                        |}""".stripMargin)

        And("Both tasks should be requested to be killed")
        verify(taskKiller).kill(Matchers.eq(app1), any, any)(any)
        verify(taskKiller).kill(Matchers.eq(app2), any, any)(any)

        And("Nothing else should be called on the TaskKiller")
        noMoreInteractions(taskKiller)
      }
    }

    "try to kill pod instances" in new Fixture {
      Given("two apps and 1 task each")
      val pod1 = "/pod".toRootPath

      val instance = TestInstanceBuilder.newBuilder(pod1).addTaskRunning(Some("container1")).getInstance()

      val (container, _) = instance.tasksMap.head

      val body = s"""{"ids": ["${container.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance))
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])
      groupManager.app(any) returns None

      When("we ask to kill the pod container")
      Post(Uri./.withPath(Path("/delete")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        Then("Response should be OK")
        status should be(StatusCodes.OK)

        And("No task should be called on the TaskKiller")
        noMoreInteractions(taskKiller)
      }
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

      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1, instance2))
      taskKiller.killAndScale(any, any)(any) returns Future.successful(deploymentPlan)
      groupManager.app(app1) returns Some(AppDefinition(app1))
      groupManager.app(app2) returns Some(AppDefinition(app2))

      When("we ask to kill both tasks")
      Post(Uri./.withPath(Path("/delete")).withQuery(Query("force" -> "true", "scale" -> "true")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        Then("Response should be OK")
        status should be(StatusCodes.OK)
        val expectedHeader = Headers.`Marathon-Deployment-Id`("plan")
        header(expectedHeader.name()) should be(Some(expectedHeader))

        And("Should create a deployment")
        responseAs[String] should be ("""{
                                        |  "deploymentId" : "plan",
                                        |  "version" : "1970-01-01T00:00:00Z"
                                        |}""".stripMargin)

        And("app1 and app2 is killed with force")
        verify(taskKiller).killAndScale(Matchers.eq(Map(app1 -> Seq(instance1), app2 -> Seq(instance2))), Matchers.eq(true))(any)

        And("nothing else should be called on the TaskKiller")
        noMoreInteractions(taskKiller)
      }
    }

    "killTasks with scale and wipe fails" in new Fixture {
      Given("a request")
      val app1 = "/my/app-1".toRootPath
      val taskId1 = Task.Id.forRunSpec(app1).idString
      val body = s"""{"ids": ["$taskId1"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      When("we ask to scale AND wipe")
      Post(Uri./.withPath(Path("/delete")).withQuery(Query("wipe" -> "true", "scale" -> "true")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        rejection should be (MalformedQueryParamRejection("scale, wipe", "You cannot use scale and wipe at the same time."))
      }
    }

    "killTasks with wipe delegates to taskKiller with wipe value" in new Fixture {
      Given("a task that shall be killed")
      val app1 = "/my/app-1".toRootPath
      val instance1 = TestInstanceBuilder.newBuilder(app1).addTaskRunning().getInstance()
      val List(taskId1) = instance1.tasksMap.keys.toList
      val body = s"""{"ids": ["${taskId1.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1))
      instanceTracker.specInstances(app1) returns Future.successful(Seq(instance1))
      taskKiller.kill(Matchers.eq(app1), any, Matchers.eq(true))(any) returns Future.successful(List(instance1))
      groupManager.app(app1) returns Some(AppDefinition(app1))

      When("we send the request")
      Post(Uri./.withPath(Path("/delete")).withQuery(Query("wipe" -> "true")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        Then("Response should be Bad request")
        status should be(StatusCodes.OK)

        And("the taskKiller receives the wipe flag")
        verify(taskKiller).kill(Matchers.eq(app1), any, Matchers.eq(true))(any)

        And("nothing else should be called on the TaskKiller")
        noMoreInteractions(taskKiller)
      }
    }

    {
      val controller = Fixture(authenticated = false).controller
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./.withPath(Path("/delete"))))
    }

    "fail when attempt to kill task not authorized to kill" in new Fixture(authFn = _ => false) {
      Given("two app with task")
      val app1 = "/my/app-1".toRootPath
      val instance1 = TestInstanceBuilder.newBuilder(app1).addTaskStaged().getInstance()
      val (taskId1, _) = instance1.tasksMap.head
      val body = s"""{"ids": ["${taskId1.idString}"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      instanceTracker.instancesBySpec returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance1))
      taskKiller.kill(any, any, any)(any) returns Future.successful(Seq.empty[Instance])
      groupManager.app(app1) returns Some(AppDefinition(app1))

      When("Killing task")
      Post(Uri./.withPath(Path("/delete")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        Then("Should be rejected as not authorized")
        rejection.isInstanceOf[NotAuthorized] should be (true)
      }
    }

    "killTasks fails for invalid taskId" in new Fixture {
      Given("a valid and an invalid taskId")
      val app1 = "/my/app-1".toRootPath
      val taskId1 = Task.Id.forRunSpec(app1).idString
      val body = s"""{"ids": ["$taskId1", "invalidTaskId"]}"""
      val bodyBytes = body.toCharArray.map(_.toByte)

      When("we ask to kill those two tasks")
      Post(Uri./.withPath(Path("/delete")), HttpEntity(bodyBytes).withContentType(ContentTypes.`application/json`)) ~> controller.route ~> check {
        Then("An rejection should occur that points to the invalid taskId")
        val badRequestRejection = rejection.asInstanceOf[BadRequest]
        badRequestRejection.message.message should include ("invalidTaskId")

        And("the taskKiller should not be called at all")
        verifyNoMoreInteractions(taskKiller)
      }
    }
  }

  "List tasks as txt" should {
    "list (txt) tasks with less ports than the current app version" in new Fixture {
      // Regression test for #234
      Given("one app with one task with less ports than required")
      val app = AppDefinition("/foo".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(0)))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

      val tasksByApp = InstanceTracker.InstancesBySpec.forInstances(instance)
      instanceTracker.instancesBySpec returns Future.successful(tasksByApp)

      val rootGroup = createRootGroup(apps = Map(app.id -> app))
      groupManager.rootGroup() returns rootGroup

      app.servicePorts.size should be > instance.appTask.status.networkInfo.hostPorts.size

      When("Getting the txt tasks index")
      Get(Uri./).addHeader(Accept(MediaTypes.`text/plain`)) ~> controller.route ~> check {
        Then("The status should be 200")
        status should be(StatusCodes.OK)

        responseAs[String] should include (s"foo\t0")
      }
    }

    "see only apps you are authorized to see" in {
      // Regression test for #234
      Given("one app with one task with less ports than required")
      val authorizedApp = AppDefinition("/foo".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(0)))
      val notAuthorizedApp = AppDefinition("/foo2".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(0)))
      val instance = TestInstanceBuilder.newBuilder(authorizedApp.id).addTaskRunning().getInstance()
      val notAuthorizedInstance = TestInstanceBuilder.newBuilder(notAuthorizedApp.id).addTaskRunning().getInstance()
      val f = Fixture(authFn = resource => {
        resource.asInstanceOf[AppDefinition].id == authorizedApp.id
      })

      val tasksByApp = InstanceTracker.InstancesBySpec.forInstances(notAuthorizedInstance, instance)
      f.instanceTracker.instancesBySpec returns Future.successful(tasksByApp)

      val rootGroup = createRootGroup(apps = Map(authorizedApp.id -> authorizedApp, notAuthorizedApp.id -> notAuthorizedApp))
      f.groupManager.rootGroup() returns rootGroup

      When("Getting the txt tasks index")
      Get(Uri./).addHeader(Accept(MediaTypes.`text/plain`)) ~> f.controller.route ~> check {
        Then("The status should be 200")
        status should be(StatusCodes.OK)

        responseAs[String] should include (s"foo")
        responseAs[String] should not include (s"foo2")
      }
    }

    {
      val controller = Fixture(authenticated = false).controller
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./).addHeader(Accept(MediaTypes.`text/plain`)))
    }
  }

  case class Fixture(
      authenticated: Boolean = true,
      authorized: Boolean = true,
      authFn: Any => Boolean = _ => true,
      groupManager: GroupManager = mock[GroupManager],
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager],
      taskKiller: TaskKiller = mock[TaskKiller]) {

    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    implicit val authenticator = authFixture.auth

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    val controller = new TasksController(instanceTracker, groupManager, healthCheckManager, taskKiller, electionService)

    def runningInstanceAndItsTask(app: AppDefinition, clock: Clock): (Instance, Task) = {
      val runningInstance = TestInstanceBuilder
        .newBuilder(app.id, clock.now())
        .addTaskWithBuilder().taskForStatus(mesos.Protos.TaskState.TASK_RUNNING).build()
        .getInstance()

      (runningInstance, runningInstance.tasksMap.values.head)
    }

    def stagingInstanceAndItsTask(app: AppDefinition, clock: Clock): (Instance, Task) = {
      val stagingInstance = TestInstanceBuilder
        .newBuilder(app.id, clock.now())
        .addTaskStaging()
        .getInstance()

      (stagingInstance, stagingInstance.tasksMap.values.head)
    }
  }
}
