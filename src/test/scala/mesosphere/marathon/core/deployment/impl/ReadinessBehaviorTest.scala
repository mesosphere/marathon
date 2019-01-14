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
import mesosphere.marathon.core.health.MesosCommandHealthCheck
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import mesosphere.marathon.util.CancellableOnce
import org.scalatest.concurrent.Eventually

class ReadinessBehaviorTest extends AkkaUnitTest with Eventually with GroupCreation {
  "ReadinessBehavior" should {

    "update the current frame on readiness check result" in {
      Given("an app with one instance")
      val f = new Fixture
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, Seq.empty, Frame(f.instance))

      When("the readiness check result arrives")
      actor ! f.checkIsReady

      Then("the current frame is updated")
      eventually {
        actor.underlyingActor.currentFrame.instancesReady should have size 1
        actor.underlyingActor.currentFrame.instancesReady.get(f.instanceId).value should be(true)
      }
    }

    "update the current frame on a failed readiness check result" in {
      Given("an app with one instance")
      val f = new Fixture
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, Seq.empty, Frame(f.instance))

      When("the readiness check result arrives")
      actor ! f.checkIsNotReady

      Then("the current frame is updated")
      eventually {
        actor.underlyingActor.currentFrame.instancesReady should have size 1
        actor.underlyingActor.currentFrame.instancesReady.get(f.instanceId).value should be(false)
      }
    }

    "start readiness checks" in {
      Given("An app with one instance")
      val f = new Fixture
      val appWithReadyCheck = AppDefinition(
        f.appId,
        portDefinitions = Seq(PortDefinition(123, "tcp", name = Some("http-api"))),
        versionInfo = VersionInfo.OnlyVersion(f.version),
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true"))),
        readinessChecks = Seq(ReadinessCheck("test")))
      val actor = f.readinessActor(appWithReadyCheck, Seq(f.checkIsReady), Frame(f.instance))

      When("The task becomes ready")
      actor.underlyingActor.initiateReadinessCheck(f.instance)

      Then("The target count should be reached")
      eventually {
        actor.underlyingActor.currentFrame.instancesReady should have size 1
        actor.underlyingActor.currentFrame.instancesReady.get(f.instanceId).value should be(true)
      }
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

    val checkIsReady = ReadinessCheckResult("test", taskId, ready = true, None)
    val checkIsNotReady = ReadinessCheckResult("test", taskId, ready = false, None)
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
