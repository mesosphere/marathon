package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Named

import akka.actor.ActorRef
import com.google.inject.{ Provider, Inject }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.Terminated
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Trigger rescale of affected app if a task died.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends TaskStatusUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"

  override def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {
    val taskId = task.taskId

    status.getState match {
      case Terminated(_) =>
        // Remove from our internal list
        log.info(s"initiating a scale check for app [${taskId.appId}] after $taskId terminated")
        log.info("schedulerActor: {}", schedulerActor)
        schedulerActor ! ScaleApp(taskId.appId)

      case _ =>
      // ignore
    }

    Future.successful(())
  }
}
