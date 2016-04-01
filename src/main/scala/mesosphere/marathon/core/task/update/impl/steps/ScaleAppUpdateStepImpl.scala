package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Named

import akka.actor.ActorRef
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends TaskUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {

    val terminalOrExpungedTask: Option[Task] = {
      (taskChanged.stateOp, taskChanged.stateChange) match {
        // stateOp is a terminal MesosUpdate
        case (TaskStateOp.MesosUpdate(task, MarathonTaskStatus.Terminal(_), _), _) => Some(task)
        // stateChange is an expunge (probably because we expunged a timeout reservation)
        case (_, TaskStateChange.Expunge(task)) => Some(task)
        // no ScaleApp needed
        case _ => None
      }
    }

    terminalOrExpungedTask.foreach { task =>
      log.info(s"initiating a scale check for app [${task.taskId.appId}] after ${task.taskId} terminated")
      log.info("schedulerActor: {}", schedulerActor)
      schedulerActor ! ScaleApp(task.taskId.appId)
    }

    Future.successful(())
  }
}
