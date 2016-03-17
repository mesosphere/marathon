package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.health.HealthCheckManager

import scala.concurrent.Future

/**
  * Notify the health check manager of this update.
  */
class NotifyHealthCheckManagerStepImpl @Inject() (healthCheckManager: HealthCheckManager) extends TaskUpdateStep {
  override def name: String = "notifyHealthCheckManager"

  override def processUpdate(update: TaskUpdate): Future[_] = {
    update.stateOp match {
      // forward health changes to the health check manager
      case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.WithMesosStatus(mesosStatus), now) =>
        healthCheckManager.update(mesosStatus, now)
      case _ =>
      // not interested in other task updates

    }

    Future.successful(())
  }
}
