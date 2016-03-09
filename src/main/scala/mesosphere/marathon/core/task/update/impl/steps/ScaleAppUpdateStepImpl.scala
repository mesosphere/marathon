package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Named

import akka.actor.ActorRef
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import mesosphere.marathon.core.task.update.TaskUpdateStep
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Trigger rescale of affected app if a task died.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends TaskUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"

  override def processUpdate(update: TaskUpdate): Future[_] = {

    update.stateOp match {
      case TaskStateOp.MesosUpdate(task, MarathonTaskStatus.Terminal(_), _) =>
        // Remove from our internal list
        log.info(s"initiating a scale check for app [${task.taskId.appId}] after ${task.taskId} terminated")
        log.info("schedulerActor: {}", schedulerActor)
        schedulerActor ! ScaleApp(task.taskId.appId)
      case _ =>
      // ignore
    }

    Future.successful(())
  }
}
