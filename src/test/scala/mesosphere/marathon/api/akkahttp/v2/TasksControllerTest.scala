package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ MediaTypes, StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PortDefinition }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.{ GroupCreation, SettableClock }
import org.apache.mesos
import org.scalatest.Inside

import scala.collection.immutable.Seq
import scala.concurrent.Future

class TasksControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging with GroupCreation {
  "TasksController" should {

    "lists tasks as json" in new Fixture {
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

    "list tasks as json with status filter" in new Fixture {
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

    "list tasks as json with multiple statuses" in new Fixture {
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

    "list apps when there are no apps" in new Fixture {
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
    }

    {
      val controller = Fixture(authenticated = false).controller
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./).addHeader(Accept(MediaTypes.`application/json`)), " using text/json")
    }

    "see tasks only for application to which is authorized to see" in {
      val clock = new SettableClock()
      val authorizedApp = AppDefinition(id = "/app".toPath, instances = 1)
      val notAuthorizedApp = AppDefinition(id = "/app2".toPath, instances = 1)
      val f = Fixture(authFn = resource => {
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
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager]) {

    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    implicit val authenticator = authFixture.auth

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    val controller = new TasksController(instanceTracker, groupManager, healthCheckManager, electionService)

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
