package mesosphere.marathon.state

import mesosphere.marathon.upgrade.DeploymentPlan

import scala.collection.immutable.Seq
import scala.concurrent.Future

class DeploymentRepository(val store: PersistenceStore[DeploymentPlan], val maxVersions: Option[Int] = None) extends EntityRepository[DeploymentPlan] {
  import mesosphere.util.ThreadPoolContext.context

  def store(plan: DeploymentPlan): Future[DeploymentPlan] = storeWithVersion(plan.id, plan.version, plan)

  def all(): Future[Seq[DeploymentPlan]] = {
    allIds().flatMap { ids =>
      val results = ids.map(this.currentVersion)

      Future.sequence(results).map(_.flatten.to[Seq])
    }
  }
}
