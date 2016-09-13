package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.testkit.{ TestActorRef, TestProbe }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect
import mesosphere.marathon.{ InstanceConversions, MarathonTestHelper }
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task }
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerUpdateStepProcessor }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.scalatest.{ FunSuiteLike, GivenWhenThen, Matchers }

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Most of the functionality is tested at a higher level in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]].
  */
class InstanceTrackerActorTest
    extends MarathonActorSupport with FunSuiteLike with GivenWhenThen with Mockito with Matchers with InstanceConversions {

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
    val appDataMap = InstanceTracker.InstancesBySpec.empty
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker actor gets a List query")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, InstanceTrackerActor.List)

    Then("it will eventually answer")
    probe.expectMsg(appDataMap)
  }

  test("taskTrackerActor answers with loaded data (some data)") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val task = MarathonTestHelper.mininimalTask(appId)
    val appDataMap = InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(appId, Iterable(task)))
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker actor gets a List query")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, InstanceTrackerActor.List)

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
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("the task tracker has started up")
    val probe = TestProbe()
    probe.send(f.taskTrackerActor, InstanceTrackerActor.List)
    probe.expectMsg(appDataMap)

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(1)
  }

  test("taskTrackerActor correctly updates metrics for deleted tasks") {
    val f = new Fixture
    Given("an empty task loader result")
    val appId: PathId = PathId("/app")
    val stagedTask = MarathonTestHelper.stagedTaskForApp(appId)
    val runningTask1 = MarathonTestHelper.runningTaskForApp(appId)
    val runningTask2 = MarathonTestHelper.runningTaskForApp(appId)
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("staged task gets deleted")
    val probe = TestProbe()
    val stagedUpdate = TaskStatusUpdateTestHelper.killed(stagedTask).wrapped
    val stagedAck = InstanceTrackerActor.Ack(probe.ref, stagedUpdate.stateChange)
    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(stagedUpdate, stagedAck))
    probe.expectMsg(InstanceUpdateEffect.Expunge(stagedTask))

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(0)

    When("running task gets deleted")
    val runningUpdate = TaskStatusUpdateTestHelper.killed(runningTask1).wrapped
    val runningAck = InstanceTrackerActor.Ack(probe.ref, stagedUpdate.stateChange)
    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(runningUpdate, runningAck))
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
    val stagedTask = MarathonTestHelper.stagedTaskForApp(appId)
    val runningTask1 = MarathonTestHelper.runningTaskForApp(appId)
    val runningTask2 = MarathonTestHelper.runningTaskForApp(appId)
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("staged task transitions to running")
    val probe = TestProbe()
    val stagedTaskNowRunning = MarathonTestHelper.runningTask(stagedTask.taskId.idString)
    val mesosStatus = stagedTaskNowRunning.mesosStatus.get
    val update = TaskStatusUpdateTestHelper.taskUpdateFor(
      stagedTask,
      MarathonTaskStatus(mesosStatus), mesosStatus).wrapped
    val ack = InstanceTrackerActor.Ack(probe.ref, update.stateChange)

    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(update, ack))
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
    val stagedTask = MarathonTestHelper.stagedTaskForApp(appId)
    val runningTask1 = MarathonTestHelper.runningTaskForApp(appId)
    val runningTask2 = MarathonTestHelper.runningTaskForApp(appId)
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Iterable(stagedTask, runningTask1, runningTask2))
    )
    f.taskLoader.loadTasks() returns Future.successful(appDataMap)

    When("a new staged task gets added")
    val probe = TestProbe()
    val newStagedTask = MarathonTestHelper.stagedTask(Task.Id.forRunSpec(appId).toString)
    val update = TaskStatusUpdateTestHelper.taskLaunchFor(newStagedTask).wrapped

    val ack = InstanceTrackerActor.Ack(probe.ref, update.stateChange)
    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(update, ack))
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
    lazy val taskLoader = mock[InstancesLoader]
    lazy val stepProcessor = mock[InstanceTrackerUpdateStepProcessor]
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val actorMetrics = new InstanceTrackerActor.ActorMetrics(metrics)

    stepProcessor.process(any)(any[ExecutionContext]) returns Future.successful(())

    lazy val taskTrackerActor = TestActorRef(InstanceTrackerActor.props(actorMetrics, taskLoader, stepProcessor, updaterProps))

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskLoader)
      reset(taskLoader)
    }
  }
}
