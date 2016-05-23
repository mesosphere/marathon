package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Named

import akka.actor.ActorRef
import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.MesosTaskStatus
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Trigger rescale of affected app if a task died.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActor: ActorRef) extends TaskStatusUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "scaleApp"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, task: MarathonTask, status: TaskStatus): Future[_] = {
    val taskId = status.getTaskId.getValue

    status match {
      case MesosTaskStatus.Terminal(_) | MesosTaskStatus.TemporarilyUnreachable(_) =>
        // Scale the app
        log.info(s"initiating a scale check for app [$appId] due to [$taskId] ${status.getState} ${status.getReason}")
        schedulerActor ! ScaleApp(appId)

      case _ =>
      // ignore
    }

    Future.successful(())
  }
}
