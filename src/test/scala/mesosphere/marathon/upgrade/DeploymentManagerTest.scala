package mesosphere.marathon.upgrade

import java.util.concurrent.LinkedBlockingDeque

import akka.Done
import akka.actor.{ ActorRef, Props }
import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.testkit.TestActor.{ AutoPilot, NoAutoPilot }
import akka.testkit.{ ImplicitSender, TestActor, TestActorRef, TestProbe }
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSchedulerActor.{ CommandFailed, DeploymentStarted, LoadedDeploymentsOnLeaderElection }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.storage.repository.legacy.AppEntityRepository
import mesosphere.marathon.storage.repository.legacy.store.{ InMemoryStore, MarathonStore }
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository }
import mesosphere.marathon.test.{ GroupCreation, MarathonActorSupport, MarathonTestHelper, Mockito }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.{ MarathonConf, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.rogach.scallop.ScallopConf
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, Matchers }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class DeploymentManagerTest
    extends MarathonActorSupport
    with FunSuiteLike
    with Matchers
    with BeforeAndAfter
    with Mockito
    with Eventually
    with ImplicitSender
    with GroupCreation {

  private[this] val log = LoggerFactory.getLogger(getClass)

  test("Deployment") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = createRootGroup()
    val newGroup = createRootGroup(Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(plan, ActorRef.noSender)

    awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
    manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Deploying)
  }

  test("Finished deployment") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = createRootGroup()
    val newGroup = createRootGroup(Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(plan, ActorRef.noSender)

    awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
    manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Deploying)

    manager ! DeploymentFinished(plan)
    awaitCond(manager.underlyingActor.runningDeployments.isEmpty, 5.seconds)
  }

  test("Conflicting not forced deployment") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = createRootGroup()
    val newGroup = createRootGroup(Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup, id = Some("d1"))

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(plan, ActorRef.noSender)

    awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
    manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Deploying)

    manager ! StartDeployment(plan.copy(id = "d2"), self, force = false)
    expectMsgType[CommandFailed]
    manager.underlyingActor.runningDeployments.size should be (1)
    manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Deploying)
  }

  test("Conflicting forced deployment") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = createRootGroup()
    val newGroup = createRootGroup(Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup, id = Some("b1"))

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(plan, self)
    expectMsgType[DeploymentStarted]

    awaitCond(manager.underlyingActor.runningDeployments.contains(plan.id), 5.seconds)
    manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Deploying)

    manager ! StartDeployment(plan.copy(id = "d2"), self, force = true)
    expectMsgType[DeploymentStarted]
    manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Canceling)
    eventually(manager.underlyingActor.runningDeployments("d2").status should be (DeploymentStatus.Deploying))
  }

  test("Multiple conflicting forced deployments") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = createRootGroup()
    val newGroup = createRootGroup(Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup, id = Some("d1"))

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(plan, self)
    expectMsgType[DeploymentStarted]
    manager.underlyingActor.runningDeployments("d1").status should be (DeploymentStatus.Deploying)

    manager ! StartDeployment(plan.copy(id = "d2"), self, force = true)
    expectMsgType[DeploymentStarted]
    manager.underlyingActor.runningDeployments("d1").status should be (DeploymentStatus.Canceling)
    manager.underlyingActor.runningDeployments("d2").status should be (DeploymentStatus.Deploying)

    manager ! StartDeployment(plan.copy(id = "d3"), self, force = true)
    expectMsgType[DeploymentStarted]

    // Since deployments are not really started (DeploymentActor is not spawned), DeploymentFinished event is not
    // sent and the deployments are staying in the list of runningDeployments
    manager.underlyingActor.runningDeployments("d1").status should be (DeploymentStatus.Canceling)
    manager.underlyingActor.runningDeployments("d2").status should be (DeploymentStatus.Canceling)
    manager.underlyingActor.runningDeployments("d3").status should be (DeploymentStatus.Scheduled)
  }

  test("StopActor") {
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

    val ex = new Exception

    val res = manager.underlyingActor.stopActor(probe.ref, ex)

    Await.result(res, 5.seconds) should be(true)
  }

  test("Cancel deployment") {
    val f = new Fixture
    val manager = f.deploymentManager()
    implicit val timeout = Timeout(1.minute)

    val app = AppDefinition("app".toRootPath)
    val oldGroup = createRootGroup()
    val newGroup = createRootGroup(Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(plan, self)
    expectMsgType[DeploymentStarted]

    manager ! CancelDeployment(plan.id)
    eventually(manager.underlyingActor.runningDeployments(plan.id).status should be (DeploymentStatus.Canceling))
  }

  test("Shutdown deployments") {
    val f = new Fixture
    val manager = f.deploymentManager()
    implicit val timeout = Timeout(1.minute)

    val app1 = AppDefinition("app1".toRootPath)
    val app2 = AppDefinition("app2".toRootPath)
    val oldGroup = createRootGroup()

    manager ! LoadDeploymentsOnLeaderElection
    expectMsgType[LoadedDeploymentsOnLeaderElection]

    manager ! StartDeployment(DeploymentPlan(oldGroup, createRootGroup(Map(app1.id -> app1))), ActorRef.noSender)
    manager ! StartDeployment(DeploymentPlan(oldGroup, createRootGroup(Map(app2.id -> app2))), ActorRef.noSender)
    eventually(manager.underlyingActor.runningDeployments should have size 2)

    manager ! ShutdownDeployments
    eventually(manager.underlyingActor.runningDeployments should have size 0)
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(3, Seconds))

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val deploymentRepo = mock[DeploymentRepository]
    val eventBus: EventStream = mock[EventStream]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val config: MarathonConf = new ScallopConf(Seq("--master", "foo")) with MarathonConf {
      verify()
    }
    val metrics: Metrics = new Metrics(new MetricRegistry)
    val taskTracker: InstanceTracker = MarathonTestHelper.createTaskTracker(
      AlwaysElectedLeadershipModule.forActorSystem(system)
    )
    val taskKillService: KillService = mock[KillService]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val appRepo: AppRepository = new AppEntityRepository(
      new MarathonStore[AppDefinition](new InMemoryStore, metrics, () => AppDefinition(id = PathId("/test")), prefix = "app:"),
      0
    )(ExecutionContext.global, metrics)
    val storage: StorageProvider = mock[StorageProvider]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    // A method that returns dummy props. Used to control the deployments progress. Otherwise the tests become racy
    // and depending on when DeploymentActor sends DeploymentFinished message.
    val deploymentActorProps: (Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Props = (_, _, _, _, _, _, _, _, _, _, _) => TestActor.props(new LinkedBlockingDeque())

    def deploymentManager(): TestActorRef[DeploymentManager] = TestActorRef (
      DeploymentManager.props(
        taskTracker,
        taskKillService,
        launchQueue,
        scheduler,
        storage,
        hcManager,
        eventBus,
        readinessCheckExecutor,
        deploymentRepo,
        deploymentActorProps)
    )
    deploymentRepo.store(any[DeploymentPlan]) returns Future.successful(Done)
    deploymentRepo.delete(any[String]) returns Future.successful(Done)
    deploymentRepo.all() returns Source.empty
    launchQueue.getAsync(any) returns Future.successful(None)
    launchQueue.addAsync(any, any) returns Future.successful(Done)
  }
}
