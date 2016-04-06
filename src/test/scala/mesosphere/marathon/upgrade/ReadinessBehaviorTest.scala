package mesosphere.marathon.upgrade

import akka.actor.{ ActorLogging, ActorRef, Actor }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ ReadinessCheckResult, ReadinessCheck, ReadinessCheckExecutor }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent, DeploymentStatus }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.scalatest.concurrent.Eventually
import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import rx.lang.scala.Observable
import scala.collection.immutable.Seq
import scala.concurrent.Future

class ReadinessBehaviorTest extends FunSuite with Mockito with GivenWhenThen with Matchers with MarathonActorSupport with Eventually {

  test("An app without health checks but readiness checks becomes healthy") {
    Given ("An app with one instance")
    val f = new Fixture
    var taskIsReady = false
    val appWithReadyCheck = AppDefinition(f.appId,
      portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
      versionInfo = VersionInfo.OnlyVersion(f.version),
      readinessChecks = Seq(ReadinessCheck("test")))
    val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

    When("The task becomes running")
    system.eventStream.publish(f.taskRunning)

    Then("Task should become ready")
    eventually(taskIsReady should be (true))
    actor.stop()
  }

  test("An app with health checks and readiness checks becomes healthy") {
    Given ("An app with one instance")
    val f = new Fixture
    var taskIsReady = false
    val appWithReadyCheck = AppDefinition(f.appId,
      portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
      versionInfo = VersionInfo.OnlyVersion(f.version),
      healthChecks = Set(HealthCheck()),
      readinessChecks = Seq(ReadinessCheck("test")))
    val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

    When("The task becomes healthy")
    system.eventStream.publish(f.taskRunning)
    system.eventStream.publish(f.taskIsHealthy)

    Then("Task should become ready")
    eventually(taskIsReady should be (true))
    actor.stop()
  }

  test("An app with health checks but without readiness checks becomes healthy") {
    Given ("An app with one instance")
    val f = new Fixture
    var taskIsReady = false
    val appWithReadyCheck = AppDefinition(f.appId,
      portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
      versionInfo = VersionInfo.OnlyVersion(f.version),
      healthChecks = Set(HealthCheck()))
    val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

    When("The task becomes healthy")
    system.eventStream.publish(f.taskIsHealthy)

    Then("Task should become ready")
    eventually(taskIsReady should be (true))
    actor.stop()
  }

  test("An app without health checks and without readiness checks becomes healthy") {
    Given ("An app with one instance")
    val f = new Fixture
    var taskIsReady = false
    val appWithReadyCheck = AppDefinition(f.appId,
      versionInfo = VersionInfo.OnlyVersion(f.version))
    val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

    When("The task becomes running")
    system.eventStream.publish(f.taskRunning)

    Then("Task should become ready")
    eventually(taskIsReady should be (true))
    actor.stop()
  }

  test("Readiness checks right after the task is running") {
    Given ("An app with one instance")
    val f = new Fixture
    var taskIsReady = false
    val appWithReadyCheck = AppDefinition(f.appId,
      portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
      versionInfo = VersionInfo.OnlyVersion(f.version),
      healthChecks = Set(HealthCheck()),
      readinessChecks = Seq(ReadinessCheck("test")))
    val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

    When("The task becomes running")
    system.eventStream.publish(f.taskRunning)

    Then("Task readiness checks are perfomed")
    eventually(taskIsReady should be (false))
    actor.underlyingActor.taskTargetCountReached(1) should be (false)
    eventually(actor.underlyingActor.readyTasks should have size 1)
    actor.underlyingActor.healthyTasks should have size 0

    When("The task becomes healthy")
    system.eventStream.publish(f.taskIsHealthy)

    Then("The target count should be reached")
    eventually(taskIsReady should be (true))
    eventually(actor.underlyingActor.readyTasks should have size 1)
    eventually(actor.underlyingActor.healthyTasks should have size 1)
    actor.stop()
  }

  test("A task that dies is removed from the actor") {
    Given ("An app with one instance")
    val f = new Fixture
    var taskIsReady = false
    val appWithReadyCheck = AppDefinition(f.appId,
      portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
      versionInfo = VersionInfo.OnlyVersion(f.version),
      readinessChecks = Seq(ReadinessCheck("test")))
    val actor = f.readinessActor(appWithReadyCheck, f.checkIsNotReady, _ => taskIsReady = true)
    system.eventStream.publish(f.taskRunning)
    eventually(actor.underlyingActor.healthyTasks should have size 1)

    When("The task is killed")
    actor.underlyingActor.taskTerminated(f.taskId)

    Then("Task should be removed from healthy, ready and subscriptions.")
    actor.underlyingActor.healthyTasks should be (empty)
    actor.underlyingActor.readyTasks should be (empty)
    actor.underlyingActor.subscriptionKeys should be (empty)
    actor.stop()
  }

  class Fixture {

    val deploymentManagerProbe = TestProbe()
    val step = DeploymentStep(Seq.empty)
    val plan = DeploymentPlan("deploy", Group.empty, Group.empty, Seq(step), Timestamp.now())
    val deploymentStatus = DeploymentStatus(plan, step)
    val tracker = mock[TaskTracker]
    val task = mock[Task]
    val launched = mock[Task.Launched]
    val agentInfo = mock[Task.AgentInfo]

    val appId = PathId("/test")
    val taskId = Task.Id("app.task")
    val version = Timestamp.now()
    val checkIsReady = Seq(ReadinessCheckResult("test", taskId, ready = true, None))
    val checkIsNotReady = Seq(ReadinessCheckResult("test", taskId, ready = false, None))
    val taskRunning = MesosStatusUpdateEvent(slaveId = "", taskId = taskId, taskStatus = "TASK_RUNNING",
      message = "", appId = appId, host = "", ipAddresses = None, ports = Seq.empty, version = version.toString)
    val taskIsHealthy = HealthStatusChanged(appId, taskId, version, alive = true)

    agentInfo.host returns "some.host"
    task.taskId returns taskId
    task.launched returns Some(launched)
    task.appId returns appId
    task.effectiveIpAddress(any) returns Some("some.host")
    task.agentInfo returns agentInfo
    launched.hostPorts returns Seq(1, 2, 3)
    tracker.task(any)(any) returns Future.successful(Some(task))

    def readinessActor(appDef: AppDefinition, readinessCheckResults: Seq[ReadinessCheckResult], taskReadyFn: Task.Id => Unit) = {
      val executor = new ReadinessCheckExecutor {
        override def execute(readinessCheckInfo: ReadinessCheckSpec): Observable[ReadinessCheckResult] = {
          Observable.from(readinessCheckResults)
        }
      }
      TestActorRef(new Actor with ActorLogging with ReadinessBehavior {
        override def preStart(): Unit = {
          system.eventStream.subscribe(self, classOf[MesosStatusUpdateEvent])
          system.eventStream.subscribe(self, classOf[HealthStatusChanged])
        }
        override def app: AppDefinition = appDef
        override def deploymentManager: ActorRef = deploymentManagerProbe.ref
        override def status: DeploymentStatus = deploymentStatus
        override def readinessCheckExecutor: ReadinessCheckExecutor = executor
        override def taskTracker: TaskTracker = tracker
        override def receive: Receive = readinessBehavior orElse {
          case notHandled => throw new RuntimeException(notHandled.toString)
        }
        override def taskStatusChanged(taskId: Task.Id): Unit = if (taskTargetCountReached(1)) taskReadyFn(taskId)
      }
      )
    }
  }
}
