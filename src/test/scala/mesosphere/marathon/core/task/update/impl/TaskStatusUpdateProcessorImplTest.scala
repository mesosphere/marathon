package mesosphere.marathon.core.task.update.impl

import akka.actor.ActorSystem
import com.codahale.metrics.MetricRegistry
import mesosphere.UnitTest
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.{ TestInstanceBuilder, TestTaskBuilder }
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import org.apache.mesos.SchedulerDriver

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class TaskStatusUpdateProcessorImplTest extends UnitTest {

  "The TaskStatusUpdateProcessor implementation" when {
    for {
      (origUpdate, name) <- Seq(
        (TaskStatusUpdateTestHelper.finished(), "finished"),
        (TaskStatusUpdateTestHelper.error(), "error"),
        (TaskStatusUpdateTestHelper.killed(), "killed"),
        (TaskStatusUpdateTestHelper.killing(), "killing"),
        (TaskStatusUpdateTestHelper.failed(), "failed")
      )
    } {
      s"receiving a $name task status update for an unknown task" should withFixture { f =>
        val status = origUpdate.status
        val update = origUpdate
        val instanceId = update.operation.instanceId

        f.taskTracker.instance(instanceId) returns Future.successful(None)
        f.updateProcessor.publish(status).futureValue

        "call the appropriate taskTracker method" in { verify(f.taskTracker).instance(instanceId) }
        "not issue any kill" in { noMoreInteractions(f.killService) }
        "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
        "not do anything else" in { f.verifyNoMoreInteractions() }
      }

      s"receiving a $name task status update for an unknown task that's not lost" should withFixture { f =>
        val taskToUpdate = TaskStatusUpdateTestHelper.defaultInstance
        val origUpdate = TaskStatusUpdateTestHelper.running(taskToUpdate)
        val status = origUpdate.status
        val update = origUpdate
        val instanceId = update.operation.instanceId

        f.taskTracker.instance(instanceId) returns Future.successful(None)
        f.updateProcessor.publish(status).futureValue

        "call the appropriate taskTracker method" in { verify(f.taskTracker).instance(instanceId) }
        "initiate the task kill" in { verify(f.killService).killUnknownTask(taskToUpdate.tasks.head.taskId, KillReason.Unknown) }
        "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
        "not do anything else" in { f.verifyNoMoreInteractions() }
      }
    }

    "receiving a TASK_KILLING task status update for a running task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val origUpdate = TaskStatusUpdateTestHelper.killing(instance)
      val status = origUpdate.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the the MesosStatusUpdateEvent to the stateOpProcessor" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_FAILED status update for a running task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.failed(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in {
        verify(f.taskTracker).instance(instance.instanceId)
      }
      "pass the TASK_FAILED update" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_GONE status update for a running task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.gone(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the TASK_GONE update" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_DROPPED status update for a starting task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStarting().getInstance()
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the TASK_DROPPED update" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_DROPPED status update for a staging task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the TASK_DROPPED update" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_UNREACHABLE status update for a starting task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStarting().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in {
        verify(f.taskTracker).instance(instance.instanceId)
      }
      "pass the TASK_UNREACHABLE update" in {
        verify(f.stateOpProcessor).process(instanceUpdateOp)
      }
    }

    "receiving a TASK_UNREACHABLE status update for a staging task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the TASK_UNREACHABLE update" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_UNREACHABLE status update for a running task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Update(instance, Some(instance), events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the TASK_UNREACHABLE update" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    "receiving a TASK_UNKOWN status update for an unreachable task" should withFixture { f =>
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
      val update = TaskStatusUpdateTestHelper.unknown(instance)
      val status = update.status
      val instanceUpdateOp = InstanceUpdateOperation.MesosUpdate(instance, status, f.clock.now())

      f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      f.stateOpProcessor.process(instanceUpdateOp) returns Future.successful(InstanceUpdateEffect.Expunge(instance, events = Nil))

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "pass the TASK_UNKNOWN upate" in { verify(f.stateOpProcessor).process(instanceUpdateOp) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }

    // TODO: it should be up to the Task.update function to determine whether the received update makes sense
    // if not, a reconciliation should be triggered. Before, Marathon killed those tasks
    "receiving an update for known task without launchedTask that's not lost" ignore withFixture { f =>
      val appId = PathId("/app")
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskReserved(Task.Reservation(Iterable.empty, TestTaskBuilder.Helper.taskReservationStateNew)).getInstance()
      val origUpdate = TaskStatusUpdateTestHelper.finished(instance) // everything != lost is handled in the same way
      val status = origUpdate.status

      f.taskTracker.instance(origUpdate.operation.instanceId) returns Future.successful(Some(instance))
      f.taskTracker.instance(any) returns {
        println("WTF")
        Future.successful(None)
      }

      f.updateProcessor.publish(status).futureValue

      "load the task in the task tracker" in { verify(f.taskTracker).instance(instance.instanceId) }
      "initiate the task kill" in { verify(f.killService).killInstance(instance, KillReason.Unknown) }
      "acknowledge the update" in { verify(f.schedulerDriver).acknowledgeStatusUpdate(status) }
      "not do anything else" in { f.verifyNoMoreInteractions() }
    }
  }

  lazy val appId = PathId("/app")

  def withFixture(testCode: Fixture => Any): Unit = {
    val f = new Fixture

    try { testCode(f) }
    finally f.shutdown()
  }

  class Fixture {
    implicit lazy val actorSystem: ActorSystem = ActorSystem()
    lazy val clock: ConstantClock = ConstantClock()

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
      new Metrics(new MetricRegistry),
      clock,
      taskTracker,
      stateOpProcessor,
      marathonSchedulerDriverHolder,
      killService,
      eventStream = actorSystem.eventStream
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
      noMoreInteractions(schedulerDriver)

      shutdown()
    }

    def shutdown(): Unit = {
      Await.result(actorSystem.terminate(), Duration.Inf)
    }
  }
}
