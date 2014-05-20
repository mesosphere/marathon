package mesosphere.marathon.upgrade

import akka.testkit.{TestProbe, TestActorRef, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.{FunSuiteLike, BeforeAndAfterAll, Matchers}
import org.scalatest.mock.MockitoSugar
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.upgrade.AppUpgradeManager.{Upgrade, Rollback}
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.duration._
import scala.concurrent.Await
import mesosphere.marathon.tasks.{TaskTracker, TaskQueue}
import akka.event.EventStream
import org.apache.mesos.state.InMemoryState
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.TaskUpgradeCancelledException

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
      1.second
    )
  }

  test("Rollback") {
    val driver = mock[SchedulerDriver]
    val eventBus = mock[EventStream]
    val taskQueue = mock[TaskQueue]
    val taskTracker = new TaskTracker(new InMemoryState)
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))
    val probe = TestProbe()

    manager.underlyingActor.runningUpgrades = Map("testApp" -> probe.ref)

    watch(probe.ref)

    manager ! Rollback(driver, AppDefinition(id = "testApp"), 10)

    expectTerminated(probe.ref, 1.second)

    awaitCond(
      manager.underlyingActor.runningUpgrades.contains("testApp"),
      1.second
    )
  }

  test("StopActor") {
    val eventBus = mock[EventStream]
    val taskQueue = mock[TaskQueue]
    val taskTracker = new TaskTracker(new InMemoryState)
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))
    val probe = TestProbe()

    watch(probe.ref)

    val res = manager.underlyingActor.stopActor(probe.ref)

    expectTerminated(probe.ref, 1.second)

    Await.result(res, 1.second) should be(true)
  }

  test("Cancel upgrade") {
    val driver = mock[SchedulerDriver]
    val eventBus = mock[EventStream]
    val taskQueue = mock[TaskQueue]
    val taskTracker = new TaskTracker(new InMemoryState)
    val manager = TestActorRef[AppUpgradeManager](Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))

    implicit val timeout = Timeout(1.minute)
    val res = (manager ? Upgrade(driver, AppDefinition(id = "testApp"), 10)).mapTo[Boolean]

    manager ! Rollback(driver, AppDefinition(id = "testApp"), 10)

    intercept[TaskUpgradeCancelledException] {
      Await.result(res, 1.second)
    }
  }
}
