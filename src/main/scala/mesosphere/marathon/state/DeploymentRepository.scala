package mesosphere.marathon.state

import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.upgrade.DeploymentPlan
import scala.collection.immutable.Seq
import scala.concurrent.Future

class DeploymentRepository(
  val store: EntityStore[DeploymentPlan],
  val metrics: Metrics)
    extends EntityRepository[DeploymentPlan] with StateMetrics {

  import scala.concurrent.ExecutionContext.Implicits.global

  override val maxVersions = None

  def store(plan: DeploymentPlan): Future[DeploymentPlan] = storeByName(plan.id, plan)

  def all(): Future[Seq[DeploymentPlan]] = {
    allIds().flatMap { ids =>
      val results = ids.map(this.currentVersion)

      Future.sequence(results).map(_.flatten.to[Seq])
    }
  }
}
