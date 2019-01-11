package mesosphere.marathon
package core.deployment.impl

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.stream.scaladsl.Source
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.deployment.{DeploymentPlan, DeploymentStep}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.{MesosCommandHealthCheck, MesosTcpHealthCheck}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import mesosphere.marathon.util.CancellableOnce
import org.scalatest.concurrent.Eventually

class ReadinessBehaviorTest extends AkkaUnitTest with Eventually with GroupCreation {
  "ReadinessBehavior" should {

    // TODO(karsten): migrate
    "An app without health checks but readiness checks becomes healthy" ignore {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, Frame())

      When("The task becomes running")
      system.eventStream.publish(f.instanceRunning)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    // TODO(karsten): migrate
    "An app with health checks and readiness checks becomes healthy" ignore {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, Frame())

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceRunning)
      system.eventStream.publish(f.instanceIsHealthy)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    // TODO(karsten): migrate
    "An app with health checks but without readiness checks becomes healthy" ignore {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, Frame())

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceIsHealthy)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    // TODO(karsten): migrate
    "A pod with health checks and without readiness checks becomes healthy" ignore {
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

      val actor = f.readinessActor(podWithReadyCheck, f.checkIsReady, Frame())

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceIsHealthy)

      Then("Task should become ready")
      eventually(podIsReady should be(true))
      actor.stop()
    }

    // TODO(karsten): migrate
    "An app without health checks and without readiness checks becomes healthy" ignore {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        versionInfo = VersionInfo.OnlyVersion(f.version))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, Frame())

      When("The task becomes running")
      system.eventStream.publish(f.instanceRunning)

      Then("Task should become ready")
      eventually(taskIsReady should be(true))
      actor.stop()
    }

    // TODO(karsten): migrate
    "Readiness checks right after the task is running" ignore {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsReady, Frame())

      When("The task becomes running")
      system.eventStream.publish(f.instanceRunning)

      Then("Task readiness checks are performed")
      eventually(taskIsReady should be(false))
      eventually(actor.underlyingActor.currentFrame.instancesReady should have size 1)
      actor.underlyingActor.currentFrame.instancesHealth should have size 0

      When("The task becomes healthy")
      system.eventStream.publish(f.instanceIsHealthy)

      Then("The target count should be reached")
      eventually(taskIsReady should be(true))
      eventually(actor.underlyingActor.currentFrame.instancesReady should have size 1)
      eventually(actor.underlyingActor.currentFrame.instancesHealth should have size 1)
      actor.stop()
    }

    // TODO(karsten): migrate
    "A task that dies is removed from the actor" ignore {
      Given("An app with one instance")
      val f = new Fixture
      var taskIsReady = false
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, f.checkIsNotReady, Frame())
      system.eventStream.publish(f.instanceRunning)
      eventually(actor.underlyingActor.currentFrame.instancesHealth should have size 1)

      When("The task is killed")
      //      actor.underlyingActor.instanceTerminated(f.instanceId)

      Then("Task should be removed from healthy, ready and subscriptions.")
      actor.underlyingActor.currentFrame.instancesHealth should be(empty)
      actor.underlyingActor.currentFrame.instancesReady should be(empty)
      eventually(actor.underlyingActor.subscriptionKeys should be(empty))
      actor.stop()
    }
  }

  class Fixture {

    // no port definition for port name 'http-api' was found
    val deploymentManagerProbe = TestProbe()
    val step = DeploymentStep(Seq.empty)
    val plan = DeploymentPlan("deploy", createRootGroup(), createRootGroup(), Seq(step), Timestamp.now())
    val deploymentStatus = DeploymentStatus(plan, step)
    val appId = PathId("/test")
    val app = AppDefinition(appId)
    val instanceId = Instance.Id.forRunSpec(appId)
    val taskId = Task.Id(instanceId)
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
        instanceId, Some(agentInfo), InstanceState(Running, version, Some(version), healthy = Some(true), Goal.Running),
        Map(task.taskId -> task), app, None)
      instance
    }

    val checkIsReady = Seq(ReadinessCheckResult("test", taskId, ready = true, None))
    val checkIsNotReady = Seq(ReadinessCheckResult("test", taskId, ready = false, None))
    def instanceRunning = InstanceChanged(instanceId, version, appId, Running, instance)
    val instanceIsHealthy = InstanceHealthChanged(instanceId, version, appId, healthy = Some(true))

    def readinessActor(spec: RunSpec, readinessCheckResults: Seq[ReadinessCheckResult], initialFrame: Frame) = {

      val executor = new ReadinessCheckExecutor {
        override def execute(readinessCheckInfo: ReadinessCheckSpec): Source[ReadinessCheckResult, Cancellable] = {
          Source(readinessCheckResults).mapMaterializedValue { _ => new CancellableOnce(() => ()) }
        }
      }
      TestActorRef(new Actor with ReadinessBehaviour {

        var currentFrame = initialFrame

        override def preStart(): Unit = {
          system.eventStream.subscribe(self, classOf[InstanceChanged])
          system.eventStream.subscribe(self, classOf[InstanceHealthChanged])
        }
        override val runSpec: RunSpec = spec
        override def deploymentManagerActor: ActorRef = deploymentManagerProbe.ref
        override def status: DeploymentStatus = deploymentStatus
        override def readinessCheckExecutor: ReadinessCheckExecutor = executor
        override def receive: Receive = readinessUpdates.orElse {
          case notHandled => throw new RuntimeException(notHandled.toString)
        }
      }
      )
    }
  }
}
