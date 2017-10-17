package mesosphere.marathon
package core.task.update.impl

import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.state.PathId
import org.apache.mesos.SchedulerDriver

import scala.concurrent.Future

class TaskStatusUpdateProcessorImplTest extends AkkaUnitTest {

  "The TaskStatusUpdateProcessor implementation" should {
    for {
      (origUpdate, name) <- Seq(
        (TaskStatusUpdateTestHelper.finished(), "finished"),
        (TaskStatusUpdateTestHelper.error(), "error"),
        (TaskStatusUpdateTestHelper.killed(), "killed"),
        (TaskStatusUpdateTestHelper.killing(), "killing"),
        (TaskStatusUpdateTestHelper.failed(), "failed")
      )
    } {
      s"receiving a $name task status update for an unknown task" in new Fixture {
        val status = origUpdate.status
        val update = origUpdate
        val instanceId = update.operation.instanceId

        taskTracker.instance(instanceId) returns Future.successful(None)
        updateProcessor.publish(status).futureValue

        When("call the appropriate taskTracker method")
        verify(taskTracker).instance(instanceId)
        Then("not issue any kill")
        noMoreInteractions(killService)
        Then("acknowledge the update")
        verify(schedulerDriver).acknowledgeStatusUpdate(status)
        Then("not do anything else")
        verifyNoMoreInteractions()
      }

      s"receiving a $name task status update for an unknown task that's not lost" in new Fixture {
        val instanceToUpdate = TaskStatusUpdateTestHelper.defaultInstance
        val origUpdate = TaskStatusUpdateTestHelper.running(instanceToUpdate)
        val status = origUpdate.status
        val update = origUpdate
        val instanceId = update.operation.instanceId

        taskTracker.instance(instanceId) returns Future.successful(None)
        updateProcessor.publish(status).futureValue

        When("call the appropriate taskTracker method")
        verify(taskTracker).instance(instanceId)
        Then("initiate the task kill")
        val (taskId, _) = instanceToUpdate.tasksMap.head
        verify(killService).killUnknownTask(taskId, KillReason.Unknown)
        Then("acknowledge the update")
        verify(schedulerDriver).acknowledgeStatusUpdate(status)
        Then("not do anything else")
        verifyNoMoreInteractions()
      }
    }

    "receiving a TASK_KILLING task status update for a running task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val origUpdate = TaskStatusUpdateTestHelper.killing(instance)
      val status = origUpdate.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the the MesosStatusUpdateEvent to the stateOpProcessor")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_FAILED status update for a running task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.failed(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)

      Then("pass the TASK_FAILED update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_GONE status update for a running task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.gone(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the TASK_GONE update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_DROPPED status update for a starting task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStarting().getInstance()
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the TASK_DROPPED update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_DROPPED status update for a staging task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the TASK_DROPPED update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_UNREACHABLE status update for a starting task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStarting().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)

      Then("pass the TASK_UNREACHABLE update")
      verify(stateOpProcessor).process(instanceUpdateOp)
    }

    "receiving a TASK_UNREACHABLE status update for a staging task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the TASK_UNREACHABLE update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_UNREACHABLE status update for a running task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the TASK_UNREACHABLE update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving a TASK_UNKOWN status update for an unreachable task" in new Fixture {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
      val update = TaskStatusUpdateTestHelper.unknown(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, clock.now())

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      updateProcessor.publish(status).futureValue

      When("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("pass the TASK_UNKNOWN update")
      verify(stateOpProcessor).process(instanceUpdateOp)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    // TODO: it should be up to the Task.update function to determine whether the received update makes sense
    "receiving an update for known reserved task" in new Fixture {
      val appId = PathId("/app")
      val localVolumeId = Task.LocalVolumeId(appId, "persistent-volume", "uuid")
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskReserved(localVolumeId).getInstance()
      val status = MesosTaskStatusTestHelper.finished(instance.appTask.taskId)

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      stateOpProcessor.process(any) returns Future.successful(InstanceUpdateEffect.Expunge(instance, Seq.empty[MarathonEvent]))

      When("publish the status")
      updateProcessor.publish(status).futureValue

      Then("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }

    "receiving an running update for unknown task" in new Fixture {
      val appId = PathId("/app")
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val status = MesosTaskStatusTestHelper.running(instance.appTask.taskId)

      taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      taskTracker.instance(instance.instanceId) returns Future.successful(None)

      When("publish the status")
      updateProcessor.publish(status).futureValue
      Then("load the task in the task tracker")
      verify(taskTracker).instance(instance.instanceId)
      Then("initiate the task kill")
      verify(killService).killUnknownTask(instance.appTask.taskId, KillReason.Unknown)
      Then("acknowledge the update")
      verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Then("not do anything else")
      verifyNoMoreInteractions()
    }
  }

  lazy val appId = PathId("/app")

  class Fixture {
    lazy val clock: SettableClock = new SettableClock()

    lazy val taskTracker: InstanceTracker = mock[InstanceTracker]
    lazy val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
    lazy val schedulerDriver: SchedulerDriver = mock[SchedulerDriver]
    lazy val killService: KillService = mock[KillService]
    lazy val marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(schedulerDriver)
      holder
    }

    lazy val updateProcessor = new TaskStatusUpdateProcessorImpl(
      clock,
      taskTracker,
      stateOpProcessor,
      marathonSchedulerDriverHolder,
      killService,
      eventStream = system.eventStream
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
      noMoreInteractions(schedulerDriver)
    }
  }
}
