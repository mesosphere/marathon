package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
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
    timestamp: Timestamp, appId: PathId, task: MarathonTask, status: TaskStatus): Future[_] = {
    // forward health changes to the health check manager
    healthCheckManager.update(status, Timestamp(task.getVersion))

    Future.successful(())
  }
}
