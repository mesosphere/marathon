package mesosphere.marathon
package core.task.tracker.impl

import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos.{TaskID, TaskStatus}
import akka.actor.Status
import akka.testkit.TestProbe
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.tracker.InstanceTracker.{InstancesBySpec, SpecInstances}
import mesosphere.marathon.metrics.dummy.DummyMetrics

class InstanceTrackerDelegateTest extends AkkaUnitTest {
  class Fixture {
    lazy val clock = new SettableClock()
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val taskTrackerProbe = TestProbe()
    lazy val metrics = DummyMetrics
    lazy val delegate = new InstanceTrackerDelegate(metrics, clock, config, taskTrackerProbe.ref)
    lazy val timeoutDuration = delegate.instanceTrackerQueryTimeout.duration
    def timeoutFromNow = clock.now() + timeoutDuration
  }

  "InstanceTrackerDelegate" should {
    "Launch succeeds" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance()
      val stateOp = InstanceUpdateOperation.LaunchEphemeral(instance)
      val expectedStateChange = InstanceUpdateEffect.Update(instance, None, events = Nil)

      When("process is called")
      val create = f.delegate.process(stateOp)

      Then("an update operation is requested")
      f.taskTrackerProbe.expectMsg(
        InstanceTrackerActor.UpdateContext(f.timeoutFromNow, stateOp)
      )

      When("the request is acknowledged")
      f.taskTrackerProbe.reply(expectedStateChange)
      Then("The reply is Unit, because task updates are deferred")
      create.futureValue shouldBe a[InstanceUpdateEffect.Update]
    }

    "Launch fails" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance()
      val stateOp = InstanceUpdateOperation.LaunchEphemeral(instance)

      When("process is called")
      val create = f.delegate.process(stateOp)

      Then("an update operation is requested")
      f.taskTrackerProbe.expectMsg(
        InstanceTrackerActor.UpdateContext(f.timeoutFromNow, stateOp)
      )

      When("the response is an error")
      val cause: RuntimeException = new scala.RuntimeException("test failure")
      f.taskTrackerProbe.reply(Status.Failure(cause))
      Then("The reply is the value of task")
      val createValue = create.failed.futureValue
      createValue.getMessage should include(instance.instanceId.idString)
      createValue.getMessage should include("Launch")
      createValue.getCause should be(cause)
    }

    "Expunge succeeds" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance()
      val stateOp = InstanceUpdateOperation.ForceExpunge(instance.instanceId)
      val expectedStateChange = InstanceUpdateEffect.Expunge(instance, events = Nil)

      When("terminated is called")
      val terminated = f.delegate.process(stateOp)

      Then("an expunge operation is requested")
      f.taskTrackerProbe.expectMsg(
        InstanceTrackerActor.UpdateContext(f.timeoutFromNow, stateOp)
      )

      When("the request is acknowledged")
      f.taskTrackerProbe.reply(expectedStateChange)
      Then("The reply is the value of the future")
      terminated.futureValue should be(expectedStateChange)
    }

    "Expunge fails" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance()
      val stateOp = InstanceUpdateOperation.ForceExpunge(instance.instanceId)

      When("process is called")
      val terminated = f.delegate.process(stateOp)

      Then("an expunge operation is requested")
      f.taskTrackerProbe.expectMsg(
        InstanceTrackerActor.UpdateContext(f.timeoutFromNow, stateOp)
      )

      When("the response is an error")
      val cause: RuntimeException = new scala.RuntimeException("test failure")
      f.taskTrackerProbe.reply(Status.Failure(cause))
      Then("The reply is the value of task")
      val terminatedValue = terminated.failed.futureValue
      terminatedValue.getMessage should include(instance.instanceId.idString)
      terminatedValue.getMessage should include("Expunge")
      terminatedValue.getCause should be(cause)
    }

    "StatusUpdate succeeds" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance()
      val task: Task = instance.appTask
      val taskIdString = task.taskId.idString
      val now = f.clock.now()

      val update = TaskStatus.newBuilder().setTaskId(TaskID.newBuilder().setValue(taskIdString)).buildPartial()
      val stateOp = InstanceUpdateOperation.MesosUpdate(instance, update, now)

      When("process is called")
      val statusUpdate = f.delegate.process(stateOp)

      Then("an update operation is requested")
      f.taskTrackerProbe.expectMsg(
        InstanceTrackerActor.UpdateContext(f.timeoutFromNow, stateOp)
      )

      When("the request is acknowledged")
      val expectedStateChange = InstanceUpdateEffect.Update(instance, Some(instance), events = Nil)
      f.taskTrackerProbe.reply(expectedStateChange)
      Then("The reply is the value of the future")
      statusUpdate.futureValue should be(expectedStateChange)
    }

    "StatusUpdate fails" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId).getInstance()
      val task: Task = instance.appTask
      val taskId = task.taskId
      val now = f.clock.now()

      val update = TaskStatus.newBuilder().setTaskId(taskId.mesosTaskId).buildPartial()
      val stateOp = InstanceUpdateOperation.MesosUpdate(instance, update, now)

      When("statusUpdate is called")
      val statusUpdate = f.delegate.process(stateOp)

      Then("an update operation is requested")
      f.taskTrackerProbe.expectMsg(
        InstanceTrackerActor.UpdateContext(f.timeoutFromNow, stateOp)
      )

      When("the response is an error")
      val cause: RuntimeException = new scala.RuntimeException("test failure")
      f.taskTrackerProbe.reply(Status.Failure(cause))
      Then("The reply is the value of task")
      val updateValue = statusUpdate.failed.futureValue
      updateValue.getMessage should include(taskId.toString)
      updateValue.getMessage should include("MesosUpdate")
      updateValue.getCause should be(cause)
    }

    "not consider resident instances as active" in {
      val f = new Fixture
      val appId: PathId = PathId("/test")
      val activeCountFuture = f.delegate.countActiveSpecInstances(appId)
      var instance = TestInstanceBuilder.newBuilder(appId).addTaskReserved(None).getInstance()
      val reservedTask: Task = instance.appTask
      instance = instance.copy(tasksMap = Map(reservedTask.taskId -> reservedTask.copy(status = reservedTask.status.copy(mesosStatus = Some(MesosTaskStatusTestHelper.failed(reservedTask.taskId))))))
      f.taskTrackerProbe.expectMsg(InstanceTrackerActor.List)
      f.taskTrackerProbe.reply(InstancesBySpec(Map(appId -> SpecInstances(Map(instance.instanceId -> instance)))))
      val activeCount = activeCountFuture.futureValue
      activeCount should be(0)
    }
  }
}