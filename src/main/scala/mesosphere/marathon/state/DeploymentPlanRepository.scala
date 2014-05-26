package mesosphere.marathon.state

import scala.concurrent.Future
import mesosphere.marathon.StorageException
import scala.concurrent.ExecutionContext.Implicits.global
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.log4j.Logger

class DeploymentPlanRepository(val store: PersistenceStore[DeploymentPlan]) extends EntityRepository[DeploymentPlan] {

  private[this] val log = Logger.getLogger(getClass.getName)

  def store(plan: DeploymentPlan): Future[DeploymentPlan] = {
    log.info(s"Store deployment plan $plan")
    val key = plan.id + ID_DELIMITER + plan.version.toString
    this.store.store(plan.id, plan)
    this.store.store(key, plan).map {
      case Some(value) => value
      case None => throw new StorageException(s"Can not persist deployment plan: $plan")
    }
  }

}
