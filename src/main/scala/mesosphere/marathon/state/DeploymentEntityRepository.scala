package mesosphere.marathon.state

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.DeploymentRepository
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.CallerThreadExecutionContext

import scala.concurrent.Future

class DeploymentEntityRepository(
  val store: EntityStore[DeploymentPlan],
  val metrics: Metrics)
    extends EntityRepository[DeploymentPlan] with StateMetrics with DeploymentRepository {

  override val maxVersions = None

  override def get(id: String): Future[Option[DeploymentPlan]] = currentVersion(id)

  def delete(id: String): Future[Done] =
    expunge(id).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def store(id: String, plan: DeploymentPlan): Future[Done] =
    storeByName(id, plan).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  def ids(): Source[String, NotUsed] = Source.fromFuture(allIds()).mapConcat(identity)

  override def all(): Source[DeploymentPlan, NotUsed] = Source.fromFuture(current()).mapConcat(identity)
}
