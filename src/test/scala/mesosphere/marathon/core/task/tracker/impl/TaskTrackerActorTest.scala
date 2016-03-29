package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.testkit.{ TestActorRef, TestProbe }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.{ Task, TaskStateChange }
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.tracker.{ TaskTracker, TaskTrackerUpdateStepProcessor }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.scalatest.{ FunSuiteLike, GivenWhenThen, Matchers }

import scala.concurrent.{ ExecutionContext, Future }

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

    And("it will eventually die")
    watch(f.taskTrackerActor)
    expectMsgClass(classOf[Terminated]).getActor should be(f.taskTrackerActor)
  }

  test("taskTrackerActor answers with loaded data (empty)") {
    val f = new Fixture
    Given("an empty task loader result")
    val appDataMap = TaskTracker.TasksByApp.empty
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
    val task = MarathonTestHelper.dummyTaskProto(appId)
    val appDataMap = TaskTracker.TasksByApp.of(TaskTracker.AppTasks(appId, Iterable(task)))
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
    val stagedTask = MarathonTestHelper.stagedTaskProto("staged")
    val runningTask1 = MarathonTestHelper.runningTaskProto("running1")
    val runningTask2 = MarathonTestHelper.runningTaskProto("running2")
    val appDataMap = TaskTracker.TasksByApp.of(
      TaskTracker.AppTasks(appId, Iterable(stagedTask, runningTask1, runningTask2))
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
    val stagedTask = MarathonTestHelper.stagedTaskProto(appId)
    val stagedTaskState = TaskSerializer.fromProto(stagedTask)
    val runningTask1 = MarathonTestHelper.runningTaskProto(appId)
    val runningTask1State = TaskSerializer.fromProto(runningTask1)
    val runningTask2 = MarathonTestHelper.runningTaskProto(appId)
    val appDataMap = TaskTracker.TasksByApp.of(
      TaskTracker.AppTasks(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("staged task gets deleted")
    val probe = TestProbe()
    val stagedUpdate = TaskStatusUpdateTestHelper.lost(stagedTaskState).wrapped
    val stagedAck = TaskTrackerActor.Ack(probe.ref, stagedUpdate.stateChange)
    probe.send(f.taskTrackerActor, TaskTrackerActor.StateChanged(stagedUpdate, stagedAck))
    probe.expectMsg(TaskStateChange.Expunge(stagedTaskState))

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(0)

    When("running task gets deleted")
    val runningUpdate = TaskStatusUpdateTestHelper.lost(runningTask1State).wrapped
    val runningAck = TaskTrackerActor.Ack(probe.ref, stagedUpdate.stateChange)
    probe.send(f.taskTrackerActor, TaskTrackerActor.StateChanged(runningUpdate, runningAck))
    probe.expectMsg(())

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(1)
    f.actorMetrics.stagedCount.getValue should be(0)

    And("update steps have been processed 2 times")
    verify(f.stepProcessor, times(2)).process(any)(any[ExecutionContext])
  }

  test("taskTrackerActor correctly updates metrics for updated tasks") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTaskProto = MarathonTestHelper.stagedTaskProto(appId)
    val runningTaskProto1 = MarathonTestHelper.runningTaskProto(appId)
    val runningTaskProto2 = MarathonTestHelper.runningTaskProto(appId)
    val appDataMap = TaskTracker.TasksByApp.of(
      TaskTracker.AppTasks(appId, Iterable(stagedTaskProto, runningTaskProto1, runningTaskProto2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("staged task transitions to running")
    val probe = TestProbe()
    val stagedTaskNowRunningProto = MarathonTestHelper.runningTaskProto(stagedTaskProto.getId)
    val stagedTaskNowRunning = TaskSerializer.fromProto(stagedTaskNowRunningProto)
    val stagedTask = TaskSerializer.fromProto(stagedTaskProto)
    val update = TaskStatusUpdateTestHelper.taskUpdateFor(
      stagedTask,
      MarathonTaskStatus(stagedTaskNowRunning.mesosStatus.get)).wrapped
    val ack = TaskTrackerActor.Ack(probe.ref, update.stateChange)

    probe.send(f.taskTrackerActor, TaskTrackerActor.StateChanged(update, ack))
    probe.expectMsg(update.stateChange)

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(3)
    f.actorMetrics.stagedCount.getValue should be(0)
    And("update steps are processed")
    verify(f.stepProcessor).process(any)(any[ExecutionContext])
  }

  test("taskTrackerActor correctly updates metrics for created tasks") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTaskProto = MarathonTestHelper.stagedTaskProto(appId)
    val runningTaskProto1 = MarathonTestHelper.runningTaskProto(appId)
    val runningTaskProto2 = MarathonTestHelper.runningTaskProto(appId)
    val appDataMap = TaskTracker.TasksByApp.of(
      TaskTracker.AppTasks(appId, Iterable(stagedTaskProto, runningTaskProto1, runningTaskProto2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("a new staged task gets added")
    val probe = TestProbe()
    val newStagedTask = MarathonTestHelper.stagedTask(Task.Id.forApp(appId).toString)
    val update = TaskStatusUpdateTestHelper.taskLaunchFor(newStagedTask).wrapped

    val ack = TaskTrackerActor.Ack(probe.ref, update.stateChange)
    probe.send(f.taskTrackerActor, TaskTrackerActor.StateChanged(update, ack))
    probe.expectMsg(update.stateChange)

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(2)
    And("update steps are processed")
    verify(f.stepProcessor).process(any)(any[ExecutionContext])
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
    lazy val stepProcessor = mock[TaskTrackerUpdateStepProcessor]
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val actorMetrics = new TaskTrackerActor.ActorMetrics(metrics)

    stepProcessor.process(any)(any[ExecutionContext]) returns Future.successful(())

    lazy val taskTrackerActor = TestActorRef(TaskTrackerActor.props(actorMetrics, taskLoader, stepProcessor, updaterProps))

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskLoader)
      reset(taskLoader)
    }
  }
}
