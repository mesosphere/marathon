package mesosphere.marathon
package core.deployment.impl

import akka.actor.{ Actor, ActorRef }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.{ MesosCommandHealthCheck, MesosTcpHealthCheck }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.scalatest.concurrent.Eventually
import rx.lang.scala.Observable

import scala.concurrent.Future

class ReadinessBehaviorTest extends AkkaUnitTest with Eventually with GroupCreation {
  "ReadinessBehavior" should {
    "An app without health checks but readiness checks becomes healthy" in {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

      When("The task becomes running")
      system.eventStream.publish(f.instanceRunning)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    "An app with health checks and readiness checks becomes healthy" in {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceRunning)
      system.eventStream.publish(f.instanceIsHealthy)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    "An app with health checks but without readiness checks becomes healthy" in {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceIsHealthy)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    "A pod with health checks and without readiness checks becomes healthy" in {
      Given("An pod with one instance")
      val f = new Fixture
      var podIsReady = false
      val podWithReadyCheck = PodDefinition(
        f.appId,
        containers = Seq(
          MesosContainer(
            name = "container",
            healthCheck = Some(MesosTcpHealthCheck()),
            resources = Resources()
          )
        ),
        versionInfo = VersionInfo.OnlyVersion(f.version)
      )

      val actor = f.readinessActor(podWithReadyCheck, f.checkIsReady, _ => podIsReady = true)

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceIsHealthy)

      Then("Task should become ready")
      eventually(podIsReady should be(true))
      actor.stop()
    }

    "An app without health checks and without readiness checks becomes healthy" in {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        versionInfo = VersionInfo.OnlyVersion(f.version))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

      When("The task becomes running")
      system.eventStream.publish(f.instanceRunning)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    "Readiness checks right after the task is running" in {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, _ => taskIsReady = true)

      When("The task becomes running")
      system.eventStream.publish(f.instanceRunning)

      Then("Task readiness checks are performed")
      eventually(taskIsReady should be(false))
      actor.underlyingActor.targetCountReached(1) should be(false)
      eventually(actor.underlyingActor.readyInstances should have size 1)
      actor.underlyingActor.healthyInstances should have size 0

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceIsHealthy)

      Then("The target count should be reached")
      eventually(taskIsReady should be(true))
      eventually(actor.underlyingActor.readyInstances should have size 1)
      eventually(actor.underlyingActor.healthyInstances should have size 1)
      actor.stop()
    }

    "A task that dies is removed from the actor" in {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsNotReady, _ => taskIsReady = true)
      system.eventStream.publish(f.instanceRunning)
      eventually(actor.underlyingActor.healthyInstances should have size 1)

      When("The task is killed")
      actor.underlyingActor.instanceTerminated(f.instanceId)

      Then("Task should be removed from healthy, ready and subscriptions.")
      actor.underlyingActor.healthyInstances should be(empty)
      actor.underlyingActor.readyInstances should be(empty)
      actor.underlyingActor.subscriptionKeys should be(empty)
      actor.stop()
    }
  }

  class Fixture {

    // no port definition for port name 'http-api' was found
    val deploymentManagerProbe = TestProbe()
    val step = DeploymentStep(Seq.empty)
    val plan = DeploymentPlan("deploy", createRootGroup(), createRootGroup(), Seq(step), Timestamp.now())
    val deploymentStatus = DeploymentStatus(plan, step)
    val tracker = mock[InstanceTracker]
    val appId = PathId("/test")
    val instanceId = Instance.Id.forRunSpec(appId)
    val taskId = Task.Id.forInstanceId(instanceId, container = None)
    val hostName = "some.host"
    val agentInfo = mock[Instance.AgentInfo]
    agentInfo.host returns hostName
    val mesosStatus = MesosTaskStatusTestHelper.running(taskId)
    def mockTask = {
      val status = Task.Status(
        stagedAt = Timestamp.now(),
        startedAt = Some(Timestamp.now()),
        mesosStatus = Some(mesosStatus),
        condition = Condition.Running,
        networkInfo = NetworkInfo(hostName, hostPorts = Seq(1, 2, 3), ipAddresses = Nil))

      val t = mock[Task]
      t.taskId returns taskId
      t.runSpecId returns appId
      t.status returns status
      t
    }

    val version = Timestamp.now()

    def instance = {
      val task = mockTask
      val instance = Instance(
        instanceId, agentInfo, InstanceState(Running, version, Some(version), healthy = Some(true)),
        Map(task.taskId -> task), runSpecVersion = version, UnreachableStrategy.default())
      tracker.instance(any) returns Future.successful(Some(instance))
      instance
    }

    val checkIsReady = Seq(ReadinessCheckResult("test", taskId, ready = true, None))
    val checkIsNotReady = Seq(ReadinessCheckResult("test", taskId, ready = false, None))
    def instanceRunning = InstanceChanged(instanceId, version, appId, Running, instance)
    val instanceIsHealthy = InstanceHealthChanged(instanceId, version, appId, healthy = Some(true))

    def readinessActor(spec: RunSpec, readinessCheckResults: Seq[ReadinessCheckResult], readyFn: Instance.Id => Unit) = {
      val executor = new ReadinessCheckExecutor {
        override def execute(readinessCheckInfo: ReadinessCheckSpec): Observable[ReadinessCheckResult] = {
          Observable.from(readinessCheckResults)
        }
      }
      TestActorRef(new Actor with ReadinessBehavior {
        override def preStart(): Unit = {
          system.eventStream.subscribe(self, classOf[InstanceChanged])
          system.eventStream.subscribe(self, classOf[InstanceHealthChanged])
        }
        override def runSpec: RunSpec = spec
        override def deploymentManagerActor: ActorRef = deploymentManagerProbe.ref
        override def status: DeploymentStatus = deploymentStatus
        override def readinessCheckExecutor: ReadinessCheckExecutor = executor
        override def instanceTracker: InstanceTracker = tracker
        override def receive: Receive = readinessBehavior orElse {
          case notHandled => throw new RuntimeException(notHandled.toString)
        }
        override def instanceConditionChanged(instanceId: Instance.Id): Unit = if (targetCountReached(1)) readyFn(instanceId)
      }
      )
    }
  }
}
