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
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Group, MarathonStore }
import mesosphere.marathon.test.{ Mockito, MarathonActorSupport }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import mesosphere.marathon.upgrade.DeploymentManager.{ CancelDeployment, DeploymentFailed, PerformDeployment }
import mesosphere.marathon.{ MarathonConf, MarathonTestHelper, SchedulerActions }
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.SchedulerDriver
import org.rogach.scallop.ScallopConf
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
    with ImplicitSender {

  test("deploy") {
    val f = new Fixture
    val manager = f.deploymentManager()
    val app = AppDefinition("app".toRootPath)

    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Set(app))
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
    val newGroup = Group("/".toRootPath, Set(app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! PerformDeployment(f.driver, plan)

    manager ! CancelDeployment(plan.id)

    expectMsgType[DeploymentFailed]
  }

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val eventBus: EventStream = mock[EventStream]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val config: MarathonConf = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    val metrics: Metrics = new Metrics(new MetricRegistry)
    val taskTracker: TaskTracker = MarathonTestHelper.createTaskTracker (
      AlwaysElectedLeadershipModule.forActorSystem(system), new InMemoryStore, config, metrics
    )
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
      DeploymentManager.props(appRepo, taskTracker, launchQueue, scheduler, storage, hcManager, eventBus, readinessCheckExecutor, config)
    )

  }
}
