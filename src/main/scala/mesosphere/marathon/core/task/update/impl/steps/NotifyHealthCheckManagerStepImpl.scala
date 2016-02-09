package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Notify the health check manager of this update.
  */
class NotifyHealthCheckManagerStepImpl @Inject() (
    healthCheckManager: HealthCheckManager) extends TaskStatusUpdateStep {
  override def name: String = "notifyHealthCheckManager"

  override def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {
    // forward health changes to the health check manager
    task.launched.foreach { launched => healthCheckManager.update(status, launched.appVersion) }

    Future.successful(())
  }
}
