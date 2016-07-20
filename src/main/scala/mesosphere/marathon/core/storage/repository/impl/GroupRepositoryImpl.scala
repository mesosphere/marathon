package mesosphere.marathon.core.storage.repository.impl

// scalastyle:off
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos
import mesosphere.marathon.core.storage.repository.{ AppRepository, GroupRepository }
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }

import scala.async.Async.{ async, await }
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

private[storage] case class StoredGroup(
    id: PathId,
    appIds: Map[PathId, OffsetDateTime],
    storedGroups: Seq[StoredGroup],
    dependencies: Set[PathId],
    version: OffsetDateTime) {
  def resolve(
    groupRepository: GroupRepository,
    appRepository: AppRepository)(implicit ctx: ExecutionContext): Future[Group] = async {
    val appFutures = appIds.map { case (appId, appVersion) => appRepository.getVersion(appId, appVersion) }
    val groupFutures = storedGroups.map(_.resolve(groupRepository, appRepository))

    val apps: Map[PathId, AppDefinition] = await(Future.sequence(appFutures)).collect {
      case Some(app: AppDefinition) =>
        app.id -> app
    }(collection.breakOut)

    val groups = await(Future.sequence(groupFutures)).toSet

    Group(
      id = id,
      apps = apps,
      groups = groups,
      dependencies = dependencies,
      version = Timestamp(version)
    )
  }

  def toProto: Protos.StoredGroup = {
    import StoredGroup.DateFormat

    val apps = appIds.map {
      case (app, appVersion) =>
        Protos.StoredGroup.AppReference.newBuilder()
          .setId(app.safePath)
          .setVersion(DateFormat.format(appVersion))
          .build()
    }

    Protos.StoredGroup.newBuilder
      .setId(id.safePath)
      .addAllAppIds(apps.asJava)
      .addAllGroups(storedGroups.map(_.toProto).asJava)
      .addAllDependencies(dependencies.map(_.safePath).asJava)
      .setVersion(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(version))
      .build()
  }
}

object StoredGroup {
  val DateFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def apply(group: Group): StoredGroup =
    StoredGroup(
      id = group.id,
      appIds = group.apps.mapValues(_.version.toOffsetDateTime),
      storedGroups = group.groups.map(StoredGroup(_))(collection.breakOut),
      dependencies = group.dependencies,
      version = group.version.toOffsetDateTime)

  def apply(proto: Protos.StoredGroup): StoredGroup = {
    val apps: Map[PathId, OffsetDateTime] = proto.getAppIdsList.asScala.map { appId =>
      PathId.fromSafePath(appId.getId()) -> OffsetDateTime.parse(appId.getVersion, DateFormat)
    }(collection.breakOut)

    val groups = proto.getGroupsList.asScala.map(StoredGroup(_))

    StoredGroup(
      id = PathId.fromSafePath(proto.getId),
      appIds = apps,
      storedGroups = groups,
      dependencies = proto.getDependenciesList.asScala.map(PathId.fromSafePath)(collection.breakOut),
      version = OffsetDateTime.parse(proto.getVersion, DateFormat)
    )
  }
}

class StoredGroupRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S], appRepository: AppRepository)(
    implicit
    ir: IdResolver[PathId, StoredGroup, C, K],
    marshaller: Marshaller[StoredGroup, S],
    unmarshaller: Unmarshaller[S, StoredGroup],
    ctx: ExecutionContext
) extends GroupRepository {
  import StoredGroupRepositoryImpl._

  /* TODO: We're bypassing the cache so it won't store StoredGroup, but we should likely cache
    the root. We should probably have read-after-write consistency...
    */

  private val storedRepo = {
    def leafStore(store: PersistenceStore[K, C, S]): PersistenceStore[K, C, S] = store match {
      case s: BasePersistenceStore[K, C, S] => s
      case s: LoadTimeCachingPersistenceStore[K, C, S] => leafStore(s.store)
      case s: LazyCachingPersistenceStore[K, C, S] => leafStore(s.store)
    }
    new PersistenceStoreVersionedRepository[PathId, StoredGroup, K, C, S](leafStore(persistenceStore), _.id, _.version)
  }

  override def root(): Future[Group] = async {
    val unresolved = await(storedRepo.get(RootId))
    unresolved.map(_.resolve(this, appRepository)) match {
      case Some(group) =>
        await(group)
      case None =>
        Group.empty
    }
  }

  override def rootVersions(): Source[OffsetDateTime, NotUsed] =
    storedRepo.versions(RootId)

  override def rootVersion(version: OffsetDateTime): Future[Option[Group]] = async {
    val unresolved = await(storedRepo.getVersion(RootId, version))
    unresolved.map(_.resolve(this, appRepository)) match {
      case Some(group) =>
        Some(await(group))
      case None =>
        None
    }
  }

  override def storeRoot(group: Group): Future[Done] = async {
    val storeApps = group.apps.values.map(appRepository.store)
    await(Future.sequence(storeApps))
    await(storedRepo.store(StoredGroup(group)))
  }

  override def storeRootVersion(group: Group): Future[Done] = async {
    val storeApps = group.apps.values.map(appRepository.storeVersion)
    await(Future.sequence(storeApps))
    await(storedRepo.storeVersion(StoredGroup(group)))
  }
}

object StoredGroupRepositoryImpl {
  val RootId = PathId.empty
}
