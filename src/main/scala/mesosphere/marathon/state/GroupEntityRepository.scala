package mesosphere.marathon.state

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.GroupRepository
import mesosphere.marathon.metrics.Metrics
import mesosphere.util.CallerThreadExecutionContext

import scala.concurrent.Future

class GroupEntityRepository(
  val store: EntityStore[Group],
  val maxVersions: Option[Int] = None,
  val metrics: Metrics)
    extends EntityRepository[Group] with GroupRepository {
  import GroupEntityRepository._

  override def root(): Future[Group] = timedRead {
    store.fetch(ZkRootName)
      .map(_.getOrElse(Group.empty))(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  override def rootVersions(): Source[OffsetDateTime, NotUsed] =
    Source.fromFuture(listVersions(ZkRootName))
      .mapConcat(identity)
      .map(_.toOffsetDateTime)

  override def versionedRoot(version: OffsetDateTime): Future[Option[Group]] =
    entity(ZkRootName, Timestamp(version))

  override def storeRootVersion(group: Group, version: OffsetDateTime): Future[Done] =
    storeWithVersion(ZkRootName, Timestamp(version), group)
      .map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def storeRoot(group: Group): Future[Done] =
    storeByName(ZkRootName, group).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
}

object GroupEntityRepository {
  val ZkRootName = "/"
}
