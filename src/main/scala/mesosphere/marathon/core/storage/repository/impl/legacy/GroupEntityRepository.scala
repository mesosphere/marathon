package mesosphere.marathon.core.storage.repository.impl.legacy

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.impl.legacy.store.EntityStore
import mesosphere.marathon.core.storage.repository.{ AppRepository, GroupRepository }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, PathId }
import mesosphere.util.CallerThreadExecutionContext

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

class GroupEntityRepository(
  private[storage] val store: EntityStore[Group],
  maxVersions: Int,
  appRepository: AppRepository)(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyVersionedRepository[PathId, Group](
      store,
      maxVersions, _.safePath, PathId.fromSafePath, _.id) with GroupRepository {
  import GroupEntityRepository._

  override def root(): Future[Group] = timedRead {
    get(ZkRootName).map(_.getOrElse(Group.empty))(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  override def rootVersions(): Source[OffsetDateTime, NotUsed] =
    versions(ZkRootName)

  override def rootVersion(version: OffsetDateTime): Future[Option[Group]] =
    getVersion(ZkRootName, version)

  override def storeRoot(group: Group, updatedApps: Seq[AppDefinition], deletedApps: Seq[PathId]): Future[Done] = {
    // because the groups store their apps, we can just delete unused apps.
    async {
      val storeAppsFutures = updatedApps.map(appRepository.store)
      val deleteAppFutures = deletedApps.map(appRepository.delete)
      await(Future.sequence(storeAppsFutures))
      await(Future.sequence(deleteAppFutures).recover { case NonFatal(e) => Done })
      await(store(group))
    }
  }
}

object GroupEntityRepository {
  val ZkRootName = PathId("/")
}
