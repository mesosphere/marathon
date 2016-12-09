package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.testkit.{ TestActorRef, TestProbe }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.instance.{ TestInstanceBuilder, TestTaskBuilder }
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.TaskCondition
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
    extends MarathonActorSupport with FunSuiteLike with GivenWhenThen with Mockito with Matchers {

  test("failures while loading the initial data are escalated") {
    val f = new Fixture

    Given("a failing task loader")
    f.taskLoader.load() returns Future.failed(new RuntimeException("severe simulated loading failure"))

    When("the task tracker starts")
    f.taskTrackerActor

    Then("it will call the failing load method")
    verify(f.taskLoader).load()

    And("it will eventually die")
    watch(f.taskTrackerActor)
    expectMsgClass(classOf[Terminated]).getActor should be(f.taskTrackerActor)
  }

  test("taskTrackerActor answers with loaded data (empty)") {
    val f = new Fixture
    Given("an empty task loader result")
    val appDataMap = InstanceTracker.InstancesBySpec.empty
    f.taskLoader.load() returns Future.successful(appDataMap)

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
    val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val appDataMap = InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(appId, Seq(instance)))
    f.taskLoader.load() returns Future.successful(appDataMap)

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
    val stagedInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
    val runningInstance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val runningInstance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Seq(stagedInstance, runningInstance1, runningInstance2))
    )
    f.taskLoader.load() returns Future.successful(appDataMap)

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
    val stagedInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
    val runningInstance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val runningInstance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Seq(stagedInstance, runningInstance1, runningInstance2))
    )
    f.taskLoader.load() returns Future.successful(appDataMap)

    When("staged task gets deleted")
    val probe = TestProbe()
    val helper = TaskStatusUpdateTestHelper.killed(stagedInstance)
    val operation = helper.operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
    val stagedUpdate = helper.effect
    val (stagedTaskId, stagedTask) = stagedInstance.tasksMap.head
    val expectedTask = TestTaskBuilder.Helper.killedTask(stagedTaskId)
    val stagedAck = InstanceTrackerActor.Ack(probe.ref, stagedUpdate)
    val c = helper.wrapped.condition
    val events = f.eventsGenerator.events(helper.wrapped.condition, helper.wrapped.instance, Some(expectedTask), operation.now, stagedInstance.state.condition != helper.wrapped.condition)

    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(stagedAck))
    probe.expectMsg(InstanceUpdateEffect.Expunge(helper.wrapped.instance, events))

    Then("it will have set the correct metric counts")
    f.actorMetrics.runningCount.getValue should be(2)
    f.actorMetrics.stagedCount.getValue should be(0)

    When("running task gets deleted")
    val runningUpdate = TaskStatusUpdateTestHelper.killed(runningInstance1).effect
    val runningAck = InstanceTrackerActor.Ack(probe.ref, runningUpdate)
    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(runningAck))
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
    val stagedInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
    val runningInstance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val runningInstance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Seq(stagedInstance, runningInstance1, runningInstance2))
    )
    f.taskLoader.load() returns Future.successful(appDataMap)

    When("staged task transitions to running")
    val probe = TestProbe()
    val stagedInstanceNowRunning = TestInstanceBuilder.newBuilderWithInstanceId(stagedInstance.instanceId).addTaskRunning().getInstance()
    val (_, stagedTaskNowRunning) = stagedInstanceNowRunning.tasksMap.head
    val mesosStatus = stagedTaskNowRunning.status.mesosStatus.get
    val update = TaskStatusUpdateTestHelper.taskUpdateFor(
      stagedInstance,
      TaskCondition(mesosStatus), mesosStatus).effect
    val ack = InstanceTrackerActor.Ack(probe.ref, update)

    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(ack))
    probe.expectMsg(update)

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
    val stagedInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
    val runningInstance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val runningInstance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val appDataMap = InstanceTracker.InstancesBySpec.of(
      InstanceTracker.SpecInstances.forInstances(appId, Seq(stagedInstance, runningInstance1, runningInstance2))
    )
    f.taskLoader.load() returns Future.successful(appDataMap)

    When("a new staged task gets added")
    val probe = TestProbe()
    val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
    val update = TaskStatusUpdateTestHelper.taskLaunchFor(instance).effect

    val ack = InstanceTrackerActor.Ack(probe.ref, update)
    probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(ack))
    probe.expectMsg(update)

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

    def updaterProps(trackerRef: ActorRef): Props = spyActor // linter:ignore:UnusedParameter
    lazy val taskLoader = mock[InstancesLoader]
    lazy val stepProcessor = mock[InstanceTrackerUpdateStepProcessor]
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val actorMetrics = new InstanceTrackerActor.ActorMetrics(metrics)
    val eventsGenerator = InstanceChangedEventsGenerator

    stepProcessor.process(any)(any[ExecutionContext]) returns Future.successful(Done)

    lazy val taskTrackerActor = TestActorRef[InstanceTrackerActor](InstanceTrackerActor.props(actorMetrics, taskLoader, stepProcessor, updaterProps))

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskLoader)
      reset(taskLoader)
    }
  }
}
