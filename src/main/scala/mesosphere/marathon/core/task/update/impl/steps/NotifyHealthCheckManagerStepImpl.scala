package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }

import scala.concurrent.Future

/**
  * Notify the health check manager of this update.
  */
class NotifyHealthCheckManagerStepImpl @Inject() (healthCheckManagerProvider: Provider[HealthCheckManager])
    extends InstanceChangeHandler {
  override def name: String = "notifyHealthCheckManager"

  lazy val healthCheckManager = healthCheckManagerProvider.get

  override def process(update: InstanceChange): Future[Done] = {
    update.instance.tasksMap.valuesIterator.flatMap(_.launched).flatMap(_.status.mesosStatus).foreach { mesosStatus =>
      // TODO(PODS): the healthCheckManager should collect health status based on instances, not tasks
      healthCheckManager.update(mesosStatus, update.runSpecVersion)
    }
    Future.successful(Done)
  }
}
