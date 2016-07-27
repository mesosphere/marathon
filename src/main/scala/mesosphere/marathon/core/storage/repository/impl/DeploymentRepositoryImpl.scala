package mesosphere.marathon.core.storage.repository.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos
import mesosphere.marathon.core.storage.repository.{ DeploymentRepository, GroupRepository }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

case class StoredPlan(
    id: String,
    originalVersion: OffsetDateTime,
    targetVersion: OffsetDateTime,
    version: OffsetDateTime) extends StrictLogging {
  def resolve(groupRepository: GroupRepository)(implicit ctx: ExecutionContext): Future[Option[DeploymentPlan]] =
    async {
      val originalFuture = groupRepository.rootVersion(originalVersion)
      val targetFuture = groupRepository.rootVersion(targetVersion)
      val (original, target) = (await(originalFuture), await(targetFuture))
      (original, target) match {
        case (Some(o), Some(t)) =>
          Some(DeploymentPlan(o, t, version = Timestamp(version), id = Some(id)))
        case (Some(_), None) | (None, Some(_)) =>
          logger.error(s"While retrieving $id, either original ($original)"
            + s" or target ($target) were no longer available")
          throw new IllegalStateException("Missing target or original")
        case _ =>
          None
      }
    }

  def toProto: Protos.DeploymentPlanDefinition = {
    Protos.DeploymentPlanDefinition.newBuilder
      .setId(id)
      .setOriginalRootVersion(StoredPlan.DateFormat.format(originalVersion))
      .setTargetRootVersion(StoredPlan.DateFormat.format(targetVersion))
      .setDeprecatedVersion(StoredPlan.DateFormat.format(version))
      .build()
  }
}

object StoredPlan {
  val DateFormat = StoredGroup.DateFormat

  def apply(deploymentPlan: DeploymentPlan): StoredPlan = {
    StoredPlan(deploymentPlan.id, deploymentPlan.original.version.toOffsetDateTime,
      deploymentPlan.target.version.toOffsetDateTime, deploymentPlan.version.toOffsetDateTime)
  }

  def apply(proto: Protos.DeploymentPlanDefinition): StoredPlan = {
    val version = if (proto.hasDeprecatedVersion) {
      OffsetDateTime.parse(proto.getDeprecatedVersion, DateFormat)
    } else {
      OffsetDateTime.MIN
    }
    StoredPlan(
      proto.getId,
      OffsetDateTime.parse(proto.getOriginalRootVersion, DateFormat),
      OffsetDateTime.parse(proto.getTargetRootVersion, DateFormat),
      version)
  }
}

class DeploymentRepositoryImpl[K, C, S](
    persistenceStore: PersistenceStore[K, C, S],
    groupRepository: GroupRepository)(implicit
  ir: IdResolver[String, StoredPlan, C, K],
    marshaller: Marshaller[StoredPlan, S],
    unmarshaller: Unmarshaller[S, StoredPlan],
    ctx: ExecutionContext) extends DeploymentRepository {

  val repo = new PersistenceStoreRepository[String, StoredPlan, K, C, S](persistenceStore, _.id)

  override def store(v: DeploymentPlan): Future[Done] = repo.store(StoredPlan(v))

  override def delete(id: String): Future[Done] = repo.delete(id)

  override def ids(): Source[String, NotUsed] = repo.ids()

  override def all(): Source[DeploymentPlan, NotUsed] =
    repo.ids().mapAsync(Int.MaxValue)(get).collect { case Some(g) => g }

  override def get(id: String): Future[Option[DeploymentPlan]] = async {
    await(repo.get(id)) match {
      case Some(storedPlan) =>
        await(storedPlan.resolve(groupRepository))
      case None =>
        None
    }
  }
}
