package mesosphere.marathon.upgrade

import akka.actor.{ ActorRef, Props }
import akka.event.EventStream
import akka.testkit.TestActor.{ AutoPilot, NoAutoPilot }
import akka.testkit.{ ImplicitSender, TestActorRef, TestProbe }
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
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

  var driver: SchedulerDriver = _
  var eventBus: EventStream = _
  var taskQueue: LaunchQueue = _
  var config: MarathonConf = _
  var metrics: Metrics = _
  var taskTracker: TaskTracker = _
  var scheduler: SchedulerActions = _
  var appRepo: AppRepository = _
  var storage: StorageProvider = _
  var hcManager: HealthCheckManager = _

  before {
    driver = mock[SchedulerDriver]
    eventBus = mock[EventStream]
    taskQueue = mock[LaunchQueue]
    config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    metrics = new Metrics(new MetricRegistry)
    taskTracker = MarathonTestHelper.createTaskTracker(
      AlwaysElectedLeadershipModule.forActorSystem(system), new InMemoryStore, config, metrics
    )
    scheduler = mock[SchedulerActions]
    storage = mock[StorageProvider]
    appRepo = new AppRepository(
      new MarathonStore[AppDefinition](new InMemoryStore, metrics, () => AppDefinition(), prefix = "app:"),
      None,
      metrics
    )
    hcManager = mock[HealthCheckManager]
  }

  test("deploy") {
    val manager = TestActorRef[DeploymentManager](
      Props(classOf[DeploymentManager],
        appRepo, taskTracker, taskQueue, scheduler, storage, hcManager, eventBus)
    )

    val app = AppDefinition("app".toRootPath)

    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Set(app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    taskQueue.get(app.id) returns None
    manager ! PerformDeployment(driver, plan)

    awaitCond(
      manager.underlyingActor.runningDeployments.contains(plan.id),
      5.seconds
    )
  }

  test("StopActor") {
    val manager = TestActorRef[DeploymentManager](
      Props(classOf[DeploymentManager],
        appRepo, taskTracker, taskQueue, scheduler, storage, hcManager, eventBus)
    )
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
    val manager = TestActorRef[DeploymentManager](
      Props(classOf[DeploymentManager],
        appRepo, taskTracker, taskQueue, scheduler, storage, hcManager, eventBus)
    )

    implicit val timeout = Timeout(1.minute)

    val app = AppDefinition("app".toRootPath)
    val oldGroup = Group("/".toRootPath)
    val newGroup = Group("/".toRootPath, Set(app))
    val plan = DeploymentPlan(oldGroup, newGroup)

    manager ! PerformDeployment(driver, plan)

    manager ! CancelDeployment(plan.id)

    expectMsgType[DeploymentFailed]
  }
}
