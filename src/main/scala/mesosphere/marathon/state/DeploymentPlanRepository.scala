package mesosphere.marathon.state

import scala.concurrent.Future
import mesosphere.marathon.StorageException
import scala.concurrent.ExecutionContext.Implicits.global
import mesosphere.marathon.upgrade.DeploymentPlan

class DeploymentPlanRepository(val store: PersistenceStore[DeploymentPlan]) extends EntityRepository[DeploymentPlan] {

  def store(plan: DeploymentPlan): Future[DeploymentPlan] = {
    val key = plan.id + ID_DELIMITER + plan.version.toString
    this.store.store(plan.id, plan)
    this.store.store(key, plan).map {
      case Some(value) => value
      case None => throw new StorageException(s"Can not persist deployment plan: $plan")
    }
  }

}
