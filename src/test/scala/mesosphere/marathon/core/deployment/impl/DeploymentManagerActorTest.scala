package mesosphere.marathon
package core.deployment.impl

import java.util.concurrent.LinkedBlockingDeque

import akka.Done
import akka.actor.{ActorRef, Props}
import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.testkit.TestActor.{AutoPilot, NoAutoPilot}
import akka.testkit.{ImplicitSender, TestActor, TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.MarathonSchedulerActor.{DeploymentFailed, DeploymentStarted}
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.deployment.impl.DeploymentActor.Cancel
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor._
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.storage.repository.{AppRepository, DeploymentRepository}
import mesosphere.marathon.test.GroupCreation
import org.apache.mesos.SchedulerDriver
import org.rogach.scallop.ScallopConf
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class DeploymentManagerActorTest extends AkkaUnitTest with ImplicitSender with GroupCreation with Eventually {

  "DeploymentManager" should {
    "Deployment" in {
      val f = new Fixture
      val manager = f.deploymentManager()
      val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))

      val oldGroup = createRootGroup()
      val newGroup = createRootGroup(Map(app.id -> app))
      val plan = DeploymentPlan(oldGroup, newGroup)

      manager ! StartDeployment(plan, ActorRef.noSender)

      awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
      manager.underlyingActor.runningDeployments(plan.id).status should be(DeploymentStatus.Deploying)
    }

    "Finished deployment" in {
      val f = new Fixture
      val manager = f.deploymentManager()
      val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))

      val oldGroup = createRootGroup()
      val newGroup = createRootGroup(Map(app.id -> app))
      val plan = DeploymentPlan(oldGroup, newGroup)

      manager ! StartDeployment(plan, ActorRef.noSender)

      awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
      manager.underlyingActor.runningDeployments(plan.id).status should be(DeploymentStatus.Deploying)

      manager ! DeploymentFinished(plan, Success(Done))
      awaitCond(manager.underlyingActor.runningDeployments.isEmpty, 5.seconds)
    }

    "Conflicting not forced deployment" in {
      val f = new Fixture
      val manager = f.deploymentManager()
      val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))

      val oldGroup = createRootGroup()
      val newGroup = createRootGroup(Map(app.id -> app))
      val plan = DeploymentPlan(oldGroup, newGroup, id = Some("d1"))

      val probe = TestProbe()

      manager ! StartDeployment(plan, ActorRef.noSender)

      awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
      manager.underlyingActor.runningDeployments(plan.id).status should be(DeploymentStatus.Deploying)

      manager ! StartDeployment(plan.copy(id = "d2"), probe.ref, force = false)
      probe.expectMsgType[DeploymentFailed]
      manager.underlyingActor.runningDeployments.size should be (1)
      manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Deploying)
    }

    "Conflicting forced deployment" in {
      val f = new Fixture
      val manager = f.deploymentManager()
      val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))

      val oldGroup = createRootGroup()
      val newGroup = createRootGroup(Map(app.id -> app))
      val plan = DeploymentPlan(oldGroup, newGroup, id = Some("b1"))
      val probe = TestProbe()

      manager ! StartDeployment(plan, probe.ref)
      probe.expectMsgType[DeploymentStarted]

      awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
      manager.underlyingActor.runningDeployments(plan.id).status should be(DeploymentStatus.Deploying)

      manager ! StartDeployment(plan.copy(id = "d2"), probe.ref, force = true)
      probe.expectMsgType[DeploymentStarted]
      manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Canceling)
      eventually(manager.underlyingActor.runningDeployments("d2").status should be (DeploymentStatus.Deploying))
    }

    "Multiple conflicting forced deployments" in {
      val f = new Fixture
      val manager = f.deploymentManager()
      val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))

      val oldGroup = createRootGroup()
      val newGroup = createRootGroup(Map(app.id -> app))
      val plan = DeploymentPlan(oldGroup, newGroup, id = Some("d1"))
      val probe = TestProbe()

      manager ! StartDeployment(plan, probe.ref)
      probe.expectMsgType[DeploymentStarted]
      manager.underlyingActor.runningDeployments("d1").status should be (DeploymentStatus.Deploying)

      manager ! StartDeployment(plan.copy(id = "d2"), probe.ref, force = true)
      probe.expectMsgType[DeploymentStarted]
      manager.underlyingActor.runningDeployments("d1").status should be (DeploymentStatus.Canceling)
      manager.underlyingActor.runningDeployments("d2").status should be (DeploymentStatus.Deploying)

      manager ! StartDeployment(plan.copy(id = "d3"), probe.ref, force = true)
      probe.expectMsgType[DeploymentStarted]

      // Since deployments are not really started (DeploymentActor is not spawned), DeploymentFinished event is not
      // sent and the deployments are staying in the list of runningDeployments
      manager.underlyingActor.runningDeployments("d1").status should be(DeploymentStatus.Canceling)
      manager.underlyingActor.runningDeployments("d2").status should be(DeploymentStatus.Canceling)
      manager.underlyingActor.runningDeployments("d3").status should be(DeploymentStatus.Scheduled)
    }

    "StopActor" in {
      val f = new Fixture
      val manager = f.deploymentManager()
      val probe = TestProbe()

      probe.setAutoPilot(new AutoPilot {
        override def run(sender: ActorRef, msg: Any): AutoPilot = msg match {
          case Cancel(_) =>
            system.stop(probe.ref)
            NoAutoPilot
        }
      })

      val ex = new Exception("")

      val res = manager.underlyingActor.stopActor(probe.ref, ex)

      res.futureValue should be(Done)
    }

    "Cancel deployment" in {
      val f = new Fixture
      val manager = f.deploymentManager()

      val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))
      val oldGroup = createRootGroup()
      val newGroup = createRootGroup(Map(app.id -> app))
      val plan = DeploymentPlan(oldGroup, newGroup)
      val probe = TestProbe()

      manager ! StartDeployment(plan, probe.ref)
      probe.expectMsgType[DeploymentStarted]

      manager ! CancelDeployment(plan)
      eventually(manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Canceling))
    }
  }

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val deploymentRepo = mock[DeploymentRepository]
    val eventBus: EventStream = mock[EventStream]
    val config: MarathonConf = new ScallopConf(Seq("--master", "foo")) with MarathonConf {
      verify()
    }
    implicit val ctx: ExecutionContext = ExecutionContext.Implicits.global
    val scheduler: scheduling.Scheduler = mock[scheduling.Scheduler]

    val schedulerActions: SchedulerActions = mock[SchedulerActions]
    val metrics: Metrics = DummyMetrics
    val appRepo: AppRepository = AppRepository.inMemRepository(new InMemoryPersistenceStore(metrics))
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    // A method that returns dummy props. Used to control the deployments progress. Otherwise the tests become racy
    // and depending on when DeploymentActor sends DeploymentFinished message.
    val deploymentActorProps: (Any, Any, Any, Any, Any, Any, Any) => Props = (_, _, _, _, _, _, _) => TestActor.props(new LinkedBlockingDeque())

    def deploymentManager(): TestActorRef[DeploymentManagerActor] = TestActorRef (
      DeploymentManagerActor.props(
        metrics,
        schedulerActions,
        scheduler,
        hcManager,
        eventBus,
        readinessCheckExecutor,
        deploymentRepo,
        deploymentActorProps)
    )
    deploymentRepo.store(any[DeploymentPlan]) returns Future.successful(Done)
    deploymentRepo.delete(any[String]) returns Future.successful(Done)
    deploymentRepo.all() returns Source.empty
  }
}
