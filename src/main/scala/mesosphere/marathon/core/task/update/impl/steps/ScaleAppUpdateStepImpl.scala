package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Named

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
  @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef])
    extends TaskUpdateStep with InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"

  override def process(update: InstanceChange): Future[Done] = {
    // TODO(PODS): we might want to consider scaling pods in case of status Reserved or Unreachable, which are both
    // not Terminal. That should be up to a tbd InstanceLostBehavior.-
    update.status match {
      case _: InstanceStatus.Terminal =>
        val runSpecId = update.runSpecId
        val instanceId = update.id
        val state = update.status
        log.info(s"initiating a scale check for runSpec [$runSpecId] due to [$instanceId] $state")
        // TODO(PODS):
        //    1) SchedulerActor is awful
        //    2) yet it handles runSpecs locked due to deployments
        //    3) if we still use it, we should rename the Message and make the SchedulerActor generic
        schedulerActor ! ScaleApp(runSpecId)

      case _ =>
      // nothing
    }

    Future.successful(Done)
  }

  // TODO(PODS): remove this function
  override def processUpdate(taskChanged: TaskChanged): Future[_] = {

    val terminalOrExpungedTask: Option[Task] = {
      (taskChanged.stateOp, taskChanged.stateChange) match {
        // stateOp is a terminal MesosUpdate
        case (TaskStateOp.MesosUpdate(task, _: InstanceStatus.Terminal, _, _), _) => Some(task)

        // A Lost task was is being expunged
        case (TaskStateOp.MesosUpdate(_, InstanceStatus.Unreachable, mesosState, _),
          TaskStateChange.Expunge(task)) => Some(task)

        // A Lost task that might come back and is not expunged but updated
        case (TaskStateOp.MesosUpdate(_, InstanceStatus.Unreachable, mesosState, _),
          TaskStateChange.Update(task, _)) => Some(task)

        // stateChange is an expunge (probably because we expunged a timeout reservation)
        case (_, TaskStateChange.Expunge(task)) => Some(task)

        // no ScaleApp needed
        case _ => None
      }
    }

    terminalOrExpungedTask.foreach { task =>
      val appId = task.id.runSpecId
      val taskId = task.id
      // logging is accordingly old mesos.Protos.TaskState representation
      val state = ("TASK_" + task.status.taskStatus).toUpperCase
      val reason = task.mesosStatus.fold("")(status =>
        if (status.hasReason) status.getReason.toString else "")
      log.info(s"initiating a scale check for app [$appId] due to [$taskId] $state $reason")
      log.info("schedulerActor: {}", schedulerActor)
      schedulerActor ! ScaleApp(task.id.runSpecId)
    }

    Future.successful(())
  }
}
