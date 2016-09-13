package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.task.{ InstanceStateOp, Task }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }

import scala.concurrent.Future

/**
  * Notify the health check manager of this update.
  */
class NotifyHealthCheckManagerStepImpl @Inject() (healthCheckManagerProvider: Provider[HealthCheckManager])
    extends TaskUpdateStep with InstanceChangeHandler {
  override def name: String = "notifyHealthCheckManager"

  lazy val healthCheckManager = healthCheckManagerProvider.get

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    taskChanged.stateOp match {
      // forward health changes to the health check manager
      case InstanceStateOp.MesosUpdate(instance, marathonTaskStatus, mesosStatus, _) =>
        // it only makes sense to handle health check results for launched tasks
        // TODO(PODS): this is broken for TaskChanged and needs to be fixed by process(InstanceChange) below
        val task = instance.tasksMap.getOrElse(
          Task.Id(mesosStatus.getTaskId),
          throw new RuntimeException("Cannot map TaskStatus to a task in " + instance.instanceId))
        task.launched.foreach { launched =>
          healthCheckManager.update(mesosStatus, launched.runSpecVersion)
        }

      case _ =>
      // not interested in other task updates
    }

    Future.successful(())
  }

  override def process(update: InstanceChange): Future[Done] = {
    // TODO(PODS): how do we transport health status? I guess the Instance status should provide def isHealthy: Boolean
    Future.successful(Done)
  }
}
