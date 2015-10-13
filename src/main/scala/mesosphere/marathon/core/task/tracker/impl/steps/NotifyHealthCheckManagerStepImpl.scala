package mesosphere.marathon.core.task.tracker.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Notify the health check manager of this update.
  */
class NotifyHealthCheckManagerStepImpl @Inject() (
    healthCheckManager: HealthCheckManager) extends TaskStatusUpdateStep {
  override def name: String = "notifyHealthCheckManager"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], status: TaskStatus): Future[_] = {
    // forward health changes to the health check manager
    for (marathonTask <- maybeTask)
      healthCheckManager.update(status, Timestamp(marathonTask.getVersion))

    Future.successful(())
  }
}
