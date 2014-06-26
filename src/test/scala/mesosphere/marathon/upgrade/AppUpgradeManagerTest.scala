package mesosphere.marathon.upgrade

import akka.testkit.{ TestProbe, TestActorRef, TestKit }
import akka.actor.{ ActorRef, Props, ActorSystem }
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, BeforeAndAfterAll, Matchers }
import org.scalatest.mock.MockitoSugar
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.upgrade.AppUpgradeManager.{ Upgrade, CancelUpgrade }
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.duration._
import scala.concurrent.Await
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import akka.event.EventStream
import org.apache.mesos.state.InMemoryState
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.upgrade.AppUpgradeActor.Cancel
import akka.testkit.TestActor.{ NoAutoPilot, AutoPilot }
import mesosphere.marathon.{ SchedulerActions, MarathonConf }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ MarathonStore, AppRepository }

class AppUpgradeManagerTest
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
  var taskTracker: TaskTracker = _
  var scheduler: SchedulerActions = _
  var appRepo: AppRepository = _

  before {
    driver = mock[SchedulerDriver]
    eventBus = mock[EventStream]
    taskQueue = mock[TaskQueue]
    config = mock[MarathonConf]
    taskTracker = new TaskTracker(new InMemoryState, config)
    scheduler = mock[SchedulerActions]
    appRepo = new AppRepository(new MarathonStore[AppDefinition](new InMemoryState, () => AppDefinition()))
  }

  test("Upgrade") {
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], appRepo, taskTracker, taskQueue, scheduler, eventBus))

    manager ! Upgrade(driver, AppDefinition(id = "testApp".toRootPath), 10)

    awaitCond(
      manager.underlyingActor.runningUpgrades.contains("testApp".toRootPath),
      5.seconds
    )
  }

  test("StopActor") {
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], appRepo, taskTracker, taskQueue, scheduler, eventBus))
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

  test("Cancel upgrade") {
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], appRepo, taskTracker, taskQueue, scheduler, eventBus))

    implicit val timeout = Timeout(1.minute)
    val res = (manager ? Upgrade(driver, AppDefinition(id = "testApp".toRootPath), 10)).mapTo[Boolean]

    manager ! CancelUpgrade("testApp".toRootPath, new Exception)

    intercept[Exception] {
      Await.result(res, 5.seconds)
    }
  }
}
