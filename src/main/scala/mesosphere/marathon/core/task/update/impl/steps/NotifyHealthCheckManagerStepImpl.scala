package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.health.HealthCheckManager

import scala.concurrent.Future

/**
  * Notify the health check manager of this update.
  */
class NotifyHealthCheckManagerStepImpl @Inject() (healthCheckManager: HealthCheckManager) extends TaskUpdateStep {
  override def name: String = "notifyHealthCheckManager"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    taskChanged.stateOp match {
      // forward health changes to the health check manager
      case TaskStateOp.MesosUpdate(task, marathonTaskStatus, mesosStatus, _) =>
        // it only makes sense to handle health check results for launched tasks
        task.launched.foreach { launched =>
          healthCheckManager.update(mesosStatus, launched.runSpecVersion)
        }

      case _ =>
      // not interested in other task updates
    }

    Future.successful(())
  }
}
