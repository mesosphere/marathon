package mesosphere.marathon.state

import scala.concurrent.Future
import mesosphere.marathon.upgrade.DeploymentPlan

class DeploymentPlanRepository(val store: PersistenceStore[DeploymentPlan]) extends EntityRepository[DeploymentPlan] {

  def store(plan: DeploymentPlan): Future[DeploymentPlan] = storeWithVersion(plan.id, plan.version, plan)
}
