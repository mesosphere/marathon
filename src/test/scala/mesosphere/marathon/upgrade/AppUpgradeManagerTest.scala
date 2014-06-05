package mesosphere.marathon.upgrade

import akka.testkit.{ TestProbe, TestActorRef, TestKit }
import akka.actor.{ ActorRef, Props, ActorSystem }
import org.scalatest.{ FunSuiteLike, BeforeAndAfterAll, Matchers }
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
import mesosphere.marathon.TaskUpgradeCancelledException
import mesosphere.marathon.upgrade.AppUpgradeActor.Cancel
import akka.testkit.TestActor.{ NoAutoPilot, AutoPilot }

class AppUpgradeManagerTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Upgrade") {
    val driver = mock[SchedulerDriver]
    val eventBus = mock[EventStream]
    val taskQueue = mock[TaskQueue]
    val taskTracker = new TaskTracker(new InMemoryState)
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))

    manager ! Upgrade(driver, AppDefinition(id = "testApp"), 10)

    awaitCond(
      manager.underlyingActor.runningUpgrades.contains("testApp"),
      5.seconds
    )
  }

  test("StopActor") {
    val eventBus = mock[EventStream]
    val taskQueue = mock[TaskQueue]
    val taskTracker = new TaskTracker(new InMemoryState)
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))
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
    val driver = mock[SchedulerDriver]
    val eventBus = mock[EventStream]
    val taskQueue = mock[TaskQueue]
    val taskTracker = new TaskTracker(new InMemoryState)
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))

    implicit val timeout = Timeout(1.minute)
    val res = (manager ? Upgrade(driver, AppDefinition(id = "testApp"), 10)).mapTo[Boolean]

    manager ! CancelUpgrade("testApp", new Exception)

    intercept[Exception] {
      Await.result(res, 5.seconds)
    }
  }
}
