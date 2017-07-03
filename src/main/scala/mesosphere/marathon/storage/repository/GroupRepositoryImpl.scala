package mesosphere.marathon
package storage.repository

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository.impl.PersistenceStoreVersionedRepository
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ AppDefinition, Group, RootGroup, PathId, Timestamp }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.util.{ RichLock, toRichFuture }

import scala.annotation.tailrec
import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

private[storage] case class StoredGroup(
    id: PathId,
    appIds: Map[PathId, OffsetDateTime],
    podIds: Map[PathId, OffsetDateTime],
    storedGroups: Seq[StoredGroup],
    dependencies: Set[PathId],
    version: OffsetDateTime) extends StrictLogging {

  lazy val transitiveAppIds: Map[PathId, OffsetDateTime] = appIds ++ storedGroups.flatMap(_.appIds)
  lazy val transitivePodIds: Map[PathId, OffsetDateTime] = podIds ++ storedGroups.flatMap(_.podIds)

  @SuppressWarnings(Array("all")) // async/await
  def resolve(
    appRepository: AppRepository,
    podRepository: PodRepository)(implicit ctx: ExecutionContext): Future[Group] = async { // linter:ignore UnnecessaryElseBranch
    val appFutures = appIds.map {
      case (appId, appVersion) => appRepository.getVersion(appId, appVersion).recover {
        case NonFatal(ex) =>
          logger.error(s"Failed to load $appId:$appVersion for group $id ($version)", ex)
          None
      }
    }
    val podFutures = podIds.map {
      case (podId, podVersion) => podRepository.getVersion(podId, podVersion).recover {
        case NonFatal(ex) =>
          logger.error(s"Failed to load $podId:$podVersion for group $id ($version)", ex)
          None
      }
    }

    val groupFutures = storedGroups.map(_.resolve(appRepository, podRepository))

    val allApps = await(Future.sequence(appFutures))
    if (allApps.exists(_.isEmpty)) {
      logger.warn(s"Group $id $version is missing ${allApps.count(_.isEmpty)} apps")
    }

    val allPods = await(Future.sequence(podFutures))
    if (allPods.exists(_.isEmpty)) {
      logger.warn(s"Group $id $version is missing ${allPods.count(_.isEmpty)} pods")
    }

    val apps: Map[PathId, AppDefinition] = await(Future.sequence(appFutures)).collect {
      case Some(app: AppDefinition) =>
        app.id -> app
    }(collection.breakOut)

    val pods: Map[PathId, PodDefinition] = await(Future.sequence(podFutures)).collect {
      case Some(pod: PodDefinition) =>
        pod.id -> pod
    }(collection.breakOut)

    val groups: Map[PathId, Group] = await(Future.sequence(groupFutures)).map(group => group.id -> group)(collection.breakOut)

    Group(
      id = id,
      apps = apps,
      pods = pods,
      groupsById = groups,
      dependencies = dependencies,
      version = Timestamp(version),
      transitiveAppsById = apps ++ groups.values.flatMap(_.transitiveAppsById),
      transitivePodsById = pods ++ groups.values.flatMap(_.transitivePodsById)
    )
  }

  def toProto: Protos.GroupDefinition = {
    import StoredGroup.DateFormat

    val b = Protos.GroupDefinition.newBuilder
      .setId(id.safePath)
      .setVersion(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(version))

    appIds.foreach {
      case (app, appVersion) =>
        b.addApps(
          Protos.GroupDefinition.AppReference.newBuilder()
            .setId(app.safePath)
            .setVersion(DateFormat.format(appVersion)))
    }

    podIds.foreach {
      case (pod, podVersion) =>
        b.addPods(
          Protos.GroupDefinition.AppReference.newBuilder()
            .setId(pod.safePath)
            .setVersion(DateFormat.format(podVersion)))
    }

    storedGroups.foreach { storedGroup => b.addGroups(storedGroup.toProto) }
    dependencies.foreach { dependency => b.addDependencies(dependency.safePath) }

    b.build()
  }
}

object StoredGroup {
  val DateFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def apply(group: Group): StoredGroup =
    StoredGroup(
      id = group.id,
      appIds = group.apps.map { case (id, app) => id -> app.version.toOffsetDateTime },
      podIds = group.pods.map { case (id, pod) => id -> pod.version.toOffsetDateTime },
      storedGroups = group.groupsById.map { case (_, group) => StoredGroup(group) }(collection.breakOut),
      dependencies = group.dependencies,
      version = group.version.toOffsetDateTime)

  def apply(proto: Protos.GroupDefinition): StoredGroup = {
    val apps: Map[PathId, OffsetDateTime] = proto.getAppsList.map { appId =>
      PathId.fromSafePath(appId.getId) -> OffsetDateTime.parse(appId.getVersion, DateFormat)
    }(collection.breakOut)

    val pods: Map[PathId, OffsetDateTime] = proto.getPodsList.map { podId =>
      PathId.fromSafePath(podId.getId) -> OffsetDateTime.parse(podId.getVersion, DateFormat)
    }(collection.breakOut)

    val groups = proto.getGroupsList.map(StoredGroup(_))

    StoredGroup(
      id = PathId.fromSafePath(proto.getId),
      appIds = apps,
      podIds = pods,
      storedGroups = groups.toIndexedSeq,
      dependencies = proto.getDependenciesList.map(PathId.fromSafePath)(collection.breakOut),
      version = OffsetDateTime.parse(proto.getVersion, DateFormat)
    )
  }
}

class StoredGroupRepositoryImpl[K, C, S](
    persistenceStore: PersistenceStore[K, C, S],
    appRepository: AppRepository,
    podRepository: PodRepository,
    versionCacheMaxSize: Int = 1000)(
    implicit
    ir: IdResolver[PathId, StoredGroup, C, K],
    marshaller: Marshaller[StoredGroup, S],
    unmarshaller: Unmarshaller[S, StoredGroup],
    val ctx: ExecutionContext,
    val mat: Materializer
) extends GroupRepository with StrictLogging {
  import StoredGroupRepositoryImpl._

  /*
  Basic strategy for caching:
  get -> "wait" on the future, if it fails, create a new promise for it and actually fetch the root,
  completing the promise with the fetch result.
  set -> create a new promise for the root. If store succeeds, go update it, if it doesn't
         complete the new future with the result of the previous root future.

  This gives us read-after-write consistency.
   */
  private val lock = RichLock()
  private val rootNotLoaded: Future[RootGroup] = Future.failed[RootGroup](new Exception("Root not yet loaded"))
  private var rootFuture: Future[RootGroup] = rootNotLoaded
  private[storage] var beforeStore = Option.empty[(StoredGroup) => Future[Done]]
  private val versionCache = TrieMap.empty[OffsetDateTime, Group]

  private val storedRepo = {
    @tailrec
    def leafStore(store: PersistenceStore[K, C, S]): PersistenceStore[K, C, S] = store match {
      case s: BasePersistenceStore[K, C, S] => s
      case s: LoadTimeCachingPersistenceStore[K, C, S] => leafStore(s.store)
      case s: LazyCachingPersistenceStore[K, C, S] => leafStore(s.store)
      case s: LazyVersionCachingPersistentStore[K, C, S] => leafStore(s.store)
    }
    new PersistenceStoreVersionedRepository[PathId, StoredGroup, K, C, S](leafStore(persistenceStore), _.id, _.version)
  }

  def addToVersionCache(version: Option[OffsetDateTime], group: Group): Group = {
    if (versionCache.size > versionCacheMaxSize) {
      // remove the oldest root by default
      versionCache.remove(versionCache.minBy(_._1)._1)
    }
    versionCache.put(version.getOrElse(group.version.toOffsetDateTime), group)
    group
  }

  @SuppressWarnings(Array("all")) // async/await
  private[storage] def underlyingRoot(): Future[RootGroup] = async { // linter:ignore UnnecessaryElseBranch
    val root = await(storedRepo.get(RootId))
    val resolved = root.map(_.resolve(appRepository, podRepository))
    resolved match {
      case Some(x) => RootGroup.fromGroup(await(x))
      case None => RootGroup.empty
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def root(): Future[RootGroup] =
    async { // linter:ignore UnnecessaryElseBranch
      await(lock(rootFuture).asTry) match {
        case Failure(_) =>
          val promise = Promise[RootGroup]()
          lock {
            rootFuture = promise.future
          }
          val unresolved = await(storedRepo.get(RootId))
          val newRoot = unresolved.map(_.resolve(appRepository, podRepository)) match {
            case Some(group) =>
              RootGroup.fromGroup(await(group))
            case None =>
              // In case there is no root group yet a new (Empty) group is returned after it is persisted
              // to the repository. Otherwise attempts to read this group later would fail.
              val root = RootGroup.empty
              await(storeRoot(root, Nil, Nil, Nil, Nil))
              root
          }
          promise.success(newRoot)
          newRoot
        case Success(root) =>
          root
      }
    }

  override def invalidateGroupCache(): Future[Done] = {
    lock {
      rootFuture = rootNotLoaded
      Future.successful(Done)
    }
  }

  override def rootVersions(): Source[OffsetDateTime, NotUsed] =
    storedRepo.versions(RootId)

  @SuppressWarnings(Array("all")) // async/await
  override def rootVersion(version: OffsetDateTime): Future[Option[RootGroup]] = {
    async {
      versionCache.get(version) match {
        case Some(group) =>
          Some(RootGroup.fromGroup(group))
        case None =>
          val unresolved = await(storedRepo.getVersion(RootId, version))
          unresolved.map(_.resolve(appRepository, podRepository)) match {
            case Some(group) =>
              val resolved = await(group)
              addToVersionCache(Some(version), resolved)
              Some(RootGroup.fromGroup(resolved))
            case None =>
              logger.warn(s"Failed to load root group with version=$version")
              None
          }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def storeRoot(rootGroup: RootGroup, updatedApps: Seq[AppDefinition], deletedApps: Seq[PathId],
    updatedPods: Seq[PodDefinition], deletedPods: Seq[PathId]): Future[Done] =
    async {
      val storedGroup = StoredGroup(rootGroup)
      beforeStore match {
        case Some(preStore) =>
          await(preStore(storedGroup))
        case _ =>
      }
      val promise = Promise[RootGroup]()
      val oldRootFuture = lock {
        val old = rootFuture
        rootFuture = promise.future
        old
      }
      val storeAppFutures = updatedApps.map(appRepository.store)
      val storePodFutures = updatedPods.map(podRepository.store)
      val deleteAppFutures = deletedApps.map(appRepository.deleteCurrent)
      val deletePodFutures = deletedPods.map(podRepository.deleteCurrent)
      val storedApps = await(Future.sequence(storeAppFutures).asTry)
      val storedPods = await(Future.sequence(storePodFutures).asTry)
      await(Future.sequence(deleteAppFutures).recover { case NonFatal(e) => Done })
      await(Future.sequence(deletePodFutures).recover { case NonFatal(e) => Done })

      def revertRoot(ex: Throwable): Done = {
        promise.completeWith(oldRootFuture)
        throw ex
      }

      (storedApps, storedPods) match {
        case (Success(_), Success(_)) =>
          val storedRoot = await(storedRepo.store(storedGroup).asTry)
          storedRoot match {
            case Success(_) =>
              addToVersionCache(None, rootGroup)
              promise.success(rootGroup)
              Done
            case Failure(ex) =>
              logger.error(s"Unable to store updated group $rootGroup", ex)
              revertRoot(ex)
          }
        case (Failure(ex), Success(_)) =>
          logger.error("Unable to store updated apps or pods: " +
            s"${updatedApps.map(_.id).mkString} ${updatedPods.map(_.id).mkString}", ex)
          revertRoot(ex)
        case (Success(_), Failure(ex)) =>
          logger.error("Unable to store updated apps or pods: " +
            s"${updatedApps.map(_.id).mkString} ${updatedPods.map(_.id).mkString}", ex)
          revertRoot(ex)
        case (Failure(ex), Failure(_)) =>
          logger.error("Unable to store updated apps or pods: " +
            s"${updatedApps.map(_.id).mkString} ${updatedPods.map(_.id).mkString}", ex)
          revertRoot(ex)
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  override def storeRootVersion(rootGroup: RootGroup, updatedApps: Seq[AppDefinition], updatedPods: Seq[PodDefinition]): Future[Done] =
    async {
      val storedGroup = StoredGroup(rootGroup)
      beforeStore match {
        case Some(preStore) =>
          await(preStore(storedGroup))
        case _ =>
      }

      val storeAppFutures = updatedApps.map(appRepository.store)
      val storePodFutures = updatedPods.map(podRepository.store)
      val storedApps = await(Future.sequence(Seq(storeAppFutures, storePodFutures).flatten).asTry)

      storedApps match {
        case Success(_) =>
          val storedRoot = await(storedRepo.storeVersion(storedGroup).asTry)
          storedRoot match {
            case Success(_) =>
              addToVersionCache(None, rootGroup)
              Done
            case Failure(ex) =>
              logger.error(s"Unable to store updated group $rootGroup", ex)
              throw ex
          }
        case Failure(ex) =>
          logger.error(s"Unable to store updated apps/pods ${Seq(updatedApps, updatedPods).flatten.map(_.id).mkString}", ex)
          throw ex
      }
    }

  private[storage] def lazyRootVersion(version: OffsetDateTime): Future[Option[StoredGroup]] = {
    storedRepo.getVersion(RootId, version)
  }

  private[storage] def deleteRootVersion(version: OffsetDateTime): Future[Done] = {
    versionCache.remove(version)
    persistenceStore.deleteVersion(RootId, version)
  }

  override def appVersions(id: PathId): Source[OffsetDateTime, NotUsed] = appRepository.versions(id)

  override def appVersion(id: PathId, version: OffsetDateTime): Future[Option[AppDefinition]] = appRepository.getVersion(id, version)

  override def podVersions(id: PathId): Source[OffsetDateTime, NotUsed] = podRepository.versions(id)

  override def podVersion(id: PathId, version: OffsetDateTime): Future[Option[PodDefinition]] = podRepository.getVersion(id, version)
}

object StoredGroupRepositoryImpl {
  val RootId = PathId.empty
}
