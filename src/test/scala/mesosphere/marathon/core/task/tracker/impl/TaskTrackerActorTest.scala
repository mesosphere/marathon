package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.scalatest.{ FunSuiteLike, GivenWhenThen, Matchers }

import scala.concurrent.Future

/**
  * Most of the functionality is tested at a higher level in [[mesosphere.marathon.tasks.TaskTrackerImplTest]].
  */
class TaskTrackerActorTest
    extends MarathonActorSupport with FunSuiteLike with GivenWhenThen with Mockito with Matchers {

  test("failures while loading the initial data are escalated") {
    val f = new Fixture

    Given("a failing task loader")
    f.taskLoader.loadTasks() returns Future.failed(new RuntimeException("severe simulated loading failure"))

    When("the task tracker starts")
    f.taskTrackerActor

    Then("it will call the failing load method")
    verify(f.taskLoader).loadTasks()

    And("it will eventuall die")
    watch(f.taskTrackerActor)
    expectMsgClass(classOf[Terminated]).getActor should be(f.taskTrackerActor)
  }

  test("failures of the taskUpdateActor are escalated") {
    Given("an always failing updater")
    val f = new Fixture {
      override def updaterProps(trackerRef: ActorRef): Props = failProps
    }
    And("an empty task loader result")
    f.taskLoader.loadTasks() returns Future.successful(AppDataMap.of(Map.empty))

    When("the task tracker actor gets a ForwardTaskOp")
    f.taskTrackerActor ! TaskTrackerActor.ForwardTaskOp(PathId("/ignored"), "task1", TaskOpProcessor.Action.Noop)

    Then("it will eventuall die")
    watch(f.taskTrackerActor)
    expectMsgClass(classOf[Terminated]).getActor should be(f.taskTrackerActor)
  }

  test("taskTrackerActor answers with loaded data (empty)") {
    val f = new Fixture
    Given("an empty task loader result")
    val appDataMap = AppDataMap.of(Map.empty)
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker actor gets a List query")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, TaskTrackerActor.List)

    Then("it will eventually answer")
    probe.expectMsg(appDataMap)
  }

  test("taskTrackerActor answers with loaded data (some data)") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val task = MarathonTestHelper.dummyTask(appId)
    val appDataMap = AppDataMap.of(Map(appId -> AppData(appId, Map(task.getId -> task))))
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker actor gets a List query")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, TaskTrackerActor.List)

    Then("it will eventually answer")
    probe.expectMsg(appDataMap)
  }

  class Fixture {
    def failProps = Props(new Actor {
      override def receive: Receive = {
        case _: Any => throw new RuntimeException("severe simulated failure")
      }
    })

    lazy val spyProbe = TestProbe()

    def spyActor = Props(new Actor {
      override def receive: Receive = {
        case msg: Any => spyProbe.ref.forward(msg)
      }
    })

    def updaterProps(trackerRef: ActorRef): Props = spyActor
    lazy val taskLoader = mock[TaskLoader]

    lazy val taskTrackerActor = TestActorRef(TaskTrackerActor.props(taskLoader, updaterProps))

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskLoader)
      reset(taskLoader)
    }
  }
}
