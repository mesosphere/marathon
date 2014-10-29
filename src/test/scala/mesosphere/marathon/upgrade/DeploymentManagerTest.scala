package mesosphere.marathon.upgrade

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.pattern.ask
import akka.testkit.TestActor.{ AutoPilot, NoAutoPilot }
import akka.testkit.{ TestActorRef, TestKit, TestProbe }
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Group, MarathonStore }
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import mesosphere.marathon.upgrade.DeploymentManager.{ CancelDeployment, PerformDeployment }
import mesosphere.marathon.{ MarathonConf, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.apache.mesos.state.InMemoryState
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class DeploymentManagerTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterAll
    with MockitoSugar {

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  var driver: SchedulerDriver = _
  var eventBus: EventStream = _
  var taskQueue: TaskQueue = _
  var config: MarathonConf = _
  var registry: MetricRegistry = _
  var taskTracker: TaskTracker = _
  var scheduler: SchedulerActions = _
  var appRepo: AppRepository = _
  var storage: StorageProvider = _
  var hcManager: HealthCheckManager = _

  before {
    driver = mock[SchedulerDriver]
    eventBus = mock[EventStream]
    taskQueue = mock[TaskQueue]
    config = mock[MarathonConf]
    registry = new com.codahale.metrics.MetricRegistry
    taskTracker = new TaskTracker(new InMemoryState, config, registry)
    scheduler = mock[SchedulerActions]
    storage = mock[StorageProvider]
    appRepo = new AppRepository(
      new MarathonStore[AppDefinition](new InMemoryState, registry, () => AppDefinition()),
      None,
      registry
    )
    hcManager = mock[HealthCheckManager]
  }

  test("deploy") {
    val manager = TestActorRef[DeploymentManager](Props(classOf[DeploymentManager], appRepo, taskTracker, taskQueue, scheduler, storage, hcManager, eventBus))

    val app = AppDefinition("app".toRootPath)

    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Set(app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! PerformDeployment(driver, plan)

    awaitCond(
      manager.underlyingActor.runningDeployments.contains(plan.id),
      5.seconds
    )
  }

  test("StopActor") {
    val manager = TestActorRef[DeploymentManager](Props(classOf[DeploymentManager], appRepo, taskTracker, taskQueue, scheduler, storage, hcManager, eventBus))
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
    val manager = TestActorRef[DeploymentManager](Props(classOf[DeploymentManager], appRepo, taskTracker, taskQueue, scheduler, storage, hcManager, eventBus))

    implicit val timeout = Timeout(1.minute)

    val app = AppDefinition("app".toRootPath)
    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Set(app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    val res = manager ? PerformDeployment(driver, plan)

    manager ! CancelDeployment(plan.id, new Exception)

    intercept[Exception] {
      Await.result(res, 5.seconds)
    }
  }
}
