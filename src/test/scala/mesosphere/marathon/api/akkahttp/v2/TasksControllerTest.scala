package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ StatusCodes, Uri }
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
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.SettableClock
import org.apache.mesos
import org.scalatest.Inside

import scala.concurrent.Future

class TasksControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging {
  "TasksController" should {

    "lists tasks as json" in new Fixture {
      Given("some tasks in progress")
      val clock = new SettableClock()
      val app = AppDefinition(id = "/app".toPath, instances = 1)
      val instance1 = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id, clock.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id, clock.now()).getInstance()

      instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("app".toPath, Seq(instance1, instance2))))
      groupManager.apps(any) returns Map(app.id -> Some(app))
      healthCheckManager.statuses(any) returns Future.successful(Map.empty)

      When("Getting the tasks list")
      Get(Uri./) ~> controller.route ~> check {
        Then("All tasks should be serialized into response")
        status should be(StatusCodes.OK)

        val expected = s"""{
                         |  "tasks" : [ {
                         |    "appId" : "app",
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
                         |    "appId" : "app",
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

      instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("app".toPath, Seq(runningInstance, stagingInstance))))
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

      instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("app".toPath, Seq(stagingInstance, runningInstance))))
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
      val controller = Fixture(authorized = false).controller
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get(Uri./))
    }
  }

  case class Fixture(
      authenticated: Boolean = true,
      authorized: Boolean = true,
      groupManager: GroupManager = mock[GroupManager],
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      healthCheckManager: HealthCheckManager = mock[HealthCheckManager]) {

    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized

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
