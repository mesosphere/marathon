package mesosphere.marathon.core.storage.repository.impl.legacy

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import mesosphere.marathon.core.storage.repository.GroupRepository
import mesosphere.marathon.core.storage.repository.impl.legacy.store.EntityStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{Group, PathId}
import mesosphere.util.CallerThreadExecutionContext

import scala.concurrent.{ExecutionContext, Future}

class GroupEntityRepository(
  store: EntityStore[Group],
  maxVersions: Int)
  (implicit ctx: ExecutionContext = ExecutionContext.global, metrics: Metrics)
    extends LegacyVersionedRepository[PathId, Group](store,
      maxVersions, _.safePath, PathId.fromSafePath, _.id) with GroupRepository {
  import GroupEntityRepository._

  override def root(): Future[Group] = timedRead {
    get(ZkRootName).map(_.getOrElse(Group.empty))(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  override def rootVersions(): Source[OffsetDateTime, NotUsed] =
    versions(ZkRootName)

  override def rootVersion(version: OffsetDateTime): Future[Option[Group]] =
    getVersion(ZkRootName, version)

  override def storeRootVersion(group: Group): Future[Done] =
    storeVersion(group)

  override def storeRoot(group: Group): Future[Done] =
    store(group)
}

object GroupEntityRepository {
  val ZkRootName = PathId("/")
}
