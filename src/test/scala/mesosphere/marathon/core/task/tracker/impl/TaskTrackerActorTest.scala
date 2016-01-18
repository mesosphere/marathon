package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.testkit.{ TestActorRef, TestProbe }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, Timestamp }
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
    f.taskLoader.loadTasks() returns Future.successful(TaskTracker.AppDataMap.empty)

    When("the task tracker actor gets a ForwardTaskOp")
    val deadline = Timestamp.zero // ignored
    f.taskTrackerActor ! TaskTrackerActor.ForwardTaskOp(
      deadline, PathId("/ignored"), "task1", TaskOpProcessor.Action.Noop
    )

    Then("it will eventuall die")
    watch(f.taskTrackerActor)
    expectMsgClass(classOf[Terminated]).getActor should be(f.taskTrackerActor)
  }

  test("taskTrackerActor answers with loaded data (empty)") {
    val f = new Fixture
    Given("an empty task loader result")
    val appDataMap = TaskTracker.AppDataMap.empty
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
    val appDataMap = TaskTracker.AppDataMap.of(TaskTracker.App(appId, Map(task.getId -> task)))
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker actor gets a List query")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, TaskTrackerActor.List)

    Then("it will eventually answer")
    probe.expectMsg(appDataMap)
  }

  test("taskTrackerActor correctly calculates metrics for loaded data") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTask = MarathonTestHelper.stagedTask("staged")
    val runningTask1 = MarathonTestHelper.runningTask("running1")
    val runningTask2 = MarathonTestHelper.runningTask("running2")
    val appDataMap = TaskTracker.AppDataMap.of(
      TaskTracker.App(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker has started up")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, TaskTrackerActor.List)
    probe.expectMsg(appDataMap)

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(1)
  }

  test("taskTrackerActor correctly updates metrics for deleted tasks") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTask = MarathonTestHelper.stagedTask("staged")
    val runningTask1 = MarathonTestHelper.runningTask("running1")
    val runningTask2 = MarathonTestHelper.runningTask("running2")
    val appDataMap = TaskTracker.AppDataMap.of(
      TaskTracker.App(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("staged task gets deleted")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, TaskTrackerActor.TaskRemoved(appId, stagedTask.getId, TaskTrackerActor.Ack(probe.ref, ())))
    probe.expectMsg(())

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(0)

    When("running task gets deleted")
    probe.send(f.taskTrackerActor, TaskTrackerActor.TaskRemoved(appId, runningTask1.getId, TaskTrackerActor.Ack(probe.ref, ())))
    probe.expectMsg(())

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(1)
    f.actorMetrics.stagedCount.getValue should be(0)
  }

  test("taskTrackerActor correctly updates metrics for updated tasks") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTask = MarathonTestHelper.stagedTask("stagedThenRunning")
    val runningTask1 = MarathonTestHelper.runningTask("running1")
    val runningTask2 = MarathonTestHelper.runningTask("running2")
    val appDataMap = TaskTracker.AppDataMap.of(
      TaskTracker.App(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("staged task transitions to running")
    val probe = TestProbe()
    val stagedTaskNowRunning = MarathonTestHelper.runningTask("stagedThenRunning")
    probe.send(f.taskTrackerActor, TaskTrackerActor.TaskUpdated(appId, stagedTaskNowRunning, TaskTrackerActor.Ack(probe.ref, ())))
    probe.expectMsg(())

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(3)
    f.actorMetrics.stagedCount.getValue should be(0)
  }

  test("taskTrackerActor correctly updates metrics for created tasks") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTask = MarathonTestHelper.stagedTask("staged")
    val runningTask1 = MarathonTestHelper.runningTask("running1")
    val runningTask2 = MarathonTestHelper.runningTask("running2")
    val appDataMap = TaskTracker.AppDataMap.of(
      TaskTracker.App(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("a new staged task gets added")
    val probe = TestProbe()
    val newTask = MarathonTestHelper.stagedTask("newTask")
    probe.send(f.taskTrackerActor, TaskTrackerActor.TaskUpdated(appId, newTask, TaskTrackerActor.Ack(probe.ref, ())))
    probe.expectMsg(())

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(2)
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
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val actorMetrics = new TaskTrackerActor.ActorMetrics(metrics)

    lazy val taskTrackerActor = TestActorRef(TaskTrackerActor.props(actorMetrics, taskLoader, updaterProps))

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskLoader)
      reset(taskLoader)
    }
  }
}
