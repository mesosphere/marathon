package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.testkit.{ TestActorRef, TestProbe }
import com.typesafe.config.ConfigFactory
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.instance.{ TestInstanceBuilder, TestTaskBuilder }
import mesosphere.marathon.core.task.TaskCondition
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerUpdateStepProcessor }
import mesosphere.marathon.state.PathId

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Most of the functionality is tested at a higher level in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]].
  */
class InstanceTrackerActorTest extends AkkaUnitTest {
  override lazy val akkaConfig =
    ConfigFactory.parseString(""" akka.actor.guardian-supervisor-strategy = "akka.actor.StoppingSupervisorStrategy" """)
      .withFallback(ConfigFactory.load())

  "InstanceTrackerActor" should {
    "failures while loading the initial data are escalated" in {
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

    "taskTrackerActor answers with loaded data (empty)" in {
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

    "taskTrackerActor answers with loaded data (some data)" in {
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

    "taskTrackerActor correctly calculates metrics for loaded data" in {
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
      f.actorMetrics.runningCount.value should be(2)
      f.actorMetrics.stagedCount.value should be(1)
    }

    "taskTrackerActor correctly updates metrics for deleted tasks" in {
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
      val stagedTaskId = stagedInstance.appTask.taskId
      val expectedTask = TestTaskBuilder.Helper.killedTask(stagedTaskId)
      val stagedAck = InstanceTrackerActor.Ack(probe.ref, stagedUpdate)
      val events = f.eventsGenerator.events(helper.wrapped.instance, Some(expectedTask), operation.now, previousCondition = Some(stagedInstance.state.condition))

      probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(stagedAck))
      probe.expectMsg(InstanceUpdateEffect.Expunge(helper.wrapped.instance, events))

      Then("it will have set the correct metric counts")
      f.actorMetrics.runningCount.value should be(2)
      f.actorMetrics.stagedCount.value should be(0)

      When("running task gets deleted")
      val runningUpdate = TaskStatusUpdateTestHelper.killed(runningInstance1).effect
      val runningAck = InstanceTrackerActor.Ack(probe.ref, runningUpdate)
      probe.send(f.taskTrackerActor, InstanceTrackerActor.StateChanged(runningAck))
      probe.expectMsg(())

      Then("it will have set the correct metric counts")
      f.actorMetrics.runningCount.value should be(1)
      f.actorMetrics.stagedCount.value should be(0)

      And("update steps have been processed 2 times")
      verify(f.stepProcessor, times(2)).process(any)(any[ExecutionContext])
    }

    "taskTrackerActor correctly updates metrics for updated tasks" in {
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
      f.actorMetrics.runningCount.value should be(3)
      f.actorMetrics.stagedCount.value should be(0)
      And("update steps are processed")
      verify(f.stepProcessor).process(any)(any[ExecutionContext])
    }

    "taskTrackerActor correctly updates metrics for created tasks" in {
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
      f.actorMetrics.runningCount.value should be(2)
      f.actorMetrics.stagedCount.value should be(2)
      And("update steps are processed")
      verify(f.stepProcessor).process(any)(any[ExecutionContext])
    }
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
    lazy val actorMetrics = new InstanceTrackerActor.ActorMetrics()
    val eventsGenerator = InstanceChangedEventsGenerator

    stepProcessor.process(any)(any[ExecutionContext]) returns Future.successful(Done)

    lazy val taskTrackerActor = TestActorRef[InstanceTrackerActor](InstanceTrackerActor.props(actorMetrics, taskLoader, stepProcessor, updaterProps))

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskLoader)
      reset(taskLoader)
    }
  }
}
