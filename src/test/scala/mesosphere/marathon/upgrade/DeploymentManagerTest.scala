package mesosphere.marathon.upgrade

import akka.actor.ActorRef
import akka.event.EventStream
import akka.testkit.TestActor.{ AutoPilot, NoAutoPilot }
import akka.testkit.{ ImplicitSender, TestActorRef, TestProbe }
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.TaskKillService
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Group, MarathonStore }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import mesosphere.marathon.upgrade.DeploymentManager.{ StopAllDeployments, CancelDeployment, DeploymentFailed, PerformDeployment }
import mesosphere.marathon.{ MarathonConf, MarathonTestHelper, SchedulerActions }
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.SchedulerDriver
import org.rogach.scallop.ScallopConf
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class DeploymentManagerTest
    extends MarathonActorSupport
    with FunSuiteLike
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterAll
    with Mockito
    with Eventually
    with ImplicitSender {

  test("deploy") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    f.launchQueue.get(app.id) returns None
    manager ! PerformDeployment(f.driver, plan)

    awaitCond(
      manager.underlyingActor.runningDeployments.contains(plan.id),
      5.seconds
    )
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
    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Map(app.id -> app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! PerformDeployment(f.driver, plan)

    manager ! CancelDeployment(plan.id)

    expectMsgType[DeploymentFailed]
  }

  test("Stop All Deployments") {
    val f = new Fixture
    val manager = f.deploymentManager()
    implicit val timeout = Timeout(1.minute)

    val app1 = AppDefinition("app1".toRootPath)
    val app2 = AppDefinition("app2".toRootPath)
    val oldGroup = Group("/".toRootPath)
    manager ! PerformDeployment(f.driver, DeploymentPlan(oldGroup, Group("/".toRootPath, Map(app1.id -> app1))))
    manager ! PerformDeployment(f.driver, DeploymentPlan(oldGroup, Group("/".toRootPath, Map(app2.id -> app2))))
    eventually(manager.underlyingActor.runningDeployments should have size 2)

    manager ! StopAllDeployments
    eventually(manager.underlyingActor.runningDeployments should have size 0)
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(3, Seconds))

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val eventBus: EventStream = mock[EventStream]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val config: MarathonConf = new ScallopConf(Seq("--master", "foo")) with MarathonConf {
      verify()
    }
    val metrics: Metrics = new Metrics(new MetricRegistry)
    val taskTracker: TaskTracker = MarathonTestHelper.createTaskTracker (
      AlwaysElectedLeadershipModule.forActorSystem(system), new InMemoryStore, config, metrics
    )
    val taskKillService: TaskKillService = mock[TaskKillService]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val appRepo: AppRepository = new AppRepository(
      new MarathonStore[AppDefinition](new InMemoryStore, metrics, () => AppDefinition(), prefix = "app:"),
      None,
      metrics
    )
    val storage: StorageProvider = mock[StorageProvider]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def deploymentManager(): TestActorRef[DeploymentManager] = TestActorRef (
      DeploymentManager.props(appRepo, taskTracker, taskKillService, launchQueue, scheduler, storage, hcManager, eventBus, readinessCheckExecutor, config)
    )

  }
}
