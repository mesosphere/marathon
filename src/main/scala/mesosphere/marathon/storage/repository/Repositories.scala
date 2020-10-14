package mesosphere.marathon
package storage.repository

import java.time.OffsetDateTime

import akka.actor.ActorRefFactory
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.core.storage.repository.impl.{PersistenceStoreRepository, PersistenceStoreVersionedRepository}
import mesosphere.marathon.core.storage.store.impl.memory.{Identity, RamId}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.raml.RuntimeConfiguration
import mesosphere.marathon.state._
import mesosphere.util.state.FrameworkId

import scala.async.Async.{async, await}
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait GroupRepository {

  /** Fetch the root, returns an empty root if the root doesn't yet exist */
  def root(): Future[RootGroup]

  /** List previous versions of the root */
  def rootVersions(): Source[OffsetDateTime, NotUsed]

  /** Fetch a previous version of the root */
  def rootVersion(version: OffsetDateTime): Future[Option[RootGroup]]

  /** Resets cached root group */
  def invalidateGroupCache(): Future[Done]

  /**
    * Store the root, new/updated apps and delete apps. fails if it could not
    * update the apps or the root, but deletion errors are ignored.
    */
  def storeRoot(
      rootGroup: RootGroup,
      updatedApps: Seq[AppDefinition],
      deletedApps: Seq[AbsolutePathId],
      updatedPods: Seq[PodDefinition],
      deletedPods: Seq[AbsolutePathId]
  ): Future[Done]

  def storeRootVersion(rootGroup: RootGroup, updatedApps: Seq[AppDefinition], updatedPods: Seq[PodDefinition]): Future[Done]

  def appVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed]

  def appVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Option[AppDefinition]]

  def podVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed]

  def podVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Option[PodDefinition]]

  def runSpecVersion(id: AbsolutePathId, version: OffsetDateTime)(implicit executionContext: ExecutionContext): Future[Option[RunSpec]] = {
    appVersion(id, version).flatMap {
      case Some(app) => Future.successful(Some(app))
      case None => podVersion(id, version)
    }
  }

  def runSpecVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed] = appVersions(id) ++ podVersions(id)

  def latestRunSpec(
      id: AbsolutePathId
  )(implicit materializer: Materializer, executionContext: ExecutionContext): Future[Option[RunSpec]] = {
    runSpecVersions(id)
      .fold(Option.empty[OffsetDateTime]) {
        case (None, version) => Some(version)
        case (Some(currentMax), version) =>
          if (version.isAfter(currentMax)) Some(version)
          else Some(currentMax)
      }
      .mapAsync(parallelism = 1) {
        case Some(version) => runSpecVersion(id, version)
        case None => Future.successful(None)
      }
      .runWith(Sink.head)
  }
}

object GroupRepository {
  def zkRepository(
      store: PersistenceStore[ZkId, String, ZkSerialized],
      appRepository: AppRepository,
      podRepository: PodRepository,
      versionCacheMaxSize: Int,
      newGroupStrategy: RootGroup.NewGroupStrategy
  )(implicit ctx: ExecutionContext, mat: Materializer): StoredGroupRepositoryImpl[ZkId, String, ZkSerialized] = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new StoredGroupRepositoryImpl(store, appRepository, podRepository, versionCacheMaxSize, newGroupStrategy = newGroupStrategy)
  }

  def inMemRepository(
      store: PersistenceStore[RamId, String, Identity],
      appRepository: AppRepository,
      podRepository: PodRepository,
      versionCacheMaxSize: Int,
      newGroupStrategy: RootGroup.NewGroupStrategy
  )(implicit ctx: ExecutionContext, mat: Materializer): StoredGroupRepositoryImpl[RamId, String, Identity] = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new StoredGroupRepositoryImpl(store, appRepository, podRepository, versionCacheMaxSize, newGroupStrategy = newGroupStrategy)
  }
}

trait ReadOnlyAppRepository extends ReadOnlyVersionedRepository[AbsolutePathId, AppDefinition]
trait AppRepository extends VersionedRepository[AbsolutePathId, AppDefinition] with ReadOnlyAppRepository

object AppRepository {
  def zkRepository(
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]
  )(implicit ctx: ExecutionContext): AppRepositoryImpl[ZkId, String, ZkSerialized] = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new AppRepositoryImpl(persistenceStore)
  }

  def inMemRepository(
      persistenceStore: PersistenceStore[RamId, String, Identity]
  )(implicit ctx: ExecutionContext): AppRepositoryImpl[RamId, String, Identity] = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new AppRepositoryImpl(persistenceStore)
  }
}

trait ReadOnlyPodRepository extends ReadOnlyVersionedRepository[AbsolutePathId, PodDefinition]
trait PodRepository extends VersionedRepository[AbsolutePathId, PodDefinition] with ReadOnlyPodRepository

object PodRepository {
  def zkRepository(
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]
  )(implicit ctx: ExecutionContext): PodRepositoryImpl[ZkId, String, ZkSerialized] = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new PodRepositoryImpl(persistenceStore)
  }

  def inMemRepository(
      persistenceStore: PersistenceStore[RamId, String, Identity]
  )(implicit ctx: ExecutionContext): PodRepositoryImpl[RamId, String, Identity] = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new PodRepositoryImpl(persistenceStore)
  }
}

trait DeploymentRepository extends Repository[String, DeploymentPlan]

object DeploymentRepository {

  def zkRepository(
      metrics: Metrics,
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
      groupRepository: StoredGroupRepositoryImpl[ZkId, String, ZkSerialized],
      appRepository: AppRepositoryImpl[ZkId, String, ZkSerialized],
      podRepository: PodRepositoryImpl[ZkId, String, ZkSerialized],
      maxVersions: Int,
      storageCompactionScanBatchSize: Int,
      storageCompactionInterval: FiniteDuration
  )(implicit
      ctx: ExecutionContext,
      actorRefFactory: ActorRefFactory,
      mat: Materializer
  ): DeploymentRepositoryImpl[ZkId, String, ZkSerialized] = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new DeploymentRepositoryImpl(
      metrics,
      persistenceStore,
      groupRepository,
      appRepository,
      podRepository,
      maxVersions,
      storageCompactionScanBatchSize,
      storageCompactionInterval
    )
  }

  def inMemRepository(
      metrics: Metrics,
      persistenceStore: PersistenceStore[RamId, String, Identity],
      groupRepository: StoredGroupRepositoryImpl[RamId, String, Identity],
      appRepository: AppRepositoryImpl[RamId, String, Identity],
      podRepository: PodRepositoryImpl[RamId, String, Identity],
      maxVersions: Int,
      storageCompactionScanBatchSize: Int
  )(implicit
      ctx: ExecutionContext,
      actorRefFactory: ActorRefFactory,
      mat: Materializer
  ): DeploymentRepositoryImpl[RamId, String, Identity] = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new DeploymentRepositoryImpl(
      metrics,
      persistenceStore,
      groupRepository,
      appRepository,
      podRepository,
      maxVersions,
      storageCompactionScanBatchSize,
      0.seconds
    )
  }
}

trait InstanceRepository extends Repository[Instance.Id, state.Instance] {
  def instances(runSpecId: PathId): Source[Instance.Id, NotUsed] = {
    ids().filter(_.runSpecId == runSpecId)
  }
}

case class InstanceView(instances: InstanceRepository, groups: GroupRepository) extends StrictLogging {

  def ids(): Source[Instance.Id, NotUsed] = instances.ids()

  def store(i: Instance): Future[Done] = instances.store(state.Instance.fromCoreInstance(i))

  def delete(id: Instance.Id): Future[Done] = instances.delete(id)

  def get(id: Instance.Id)(implicit materializer: Materializer, executionContext: ExecutionContext): Future[Option[Instance]] =
    async {
      await(instances.get(id)) match {
        case None => None
        case Some(stateInstance) =>
          val runSpecId = id.runSpecId
          val runSpecVersion = stateInstance.runSpecVersion.toOffsetDateTime
          await(groups.runSpecVersion(runSpecId, runSpecVersion)) match {
            case Some(runSpec) => Some(stateInstance.toCoreInstance(runSpec))
            case None =>
              logger.warn(s"No run spec $runSpecId with version ${runSpecVersion} was found for instance ${id}. Trying latest.")
              await(groups.latestRunSpec(runSpecId)) match {
                case None =>
                  logger.warn(s"No versions found for $runSpecId at all. $id is probably orphaned."); None
                case Some(runSpec) => Some(stateInstance.toCoreInstance(runSpec))
              }
          }
      }
    }

  def all()(implicit materializer: Materializer, executionContext: ExecutionContext) =
    ids().mapAsync(RepositoryConstants.maxConcurrency)(get).collect { case Some(x) => x }
}

object InstanceRepository {

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): InstanceRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization.{instanceMarshaller, instanceResolver, instanceUnmarshaller}
    new InstanceRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): InstanceRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new InstanceRepositoryImpl(persistenceStore)
  }
}

trait TaskFailureRepository extends VersionedRepository[AbsolutePathId, TaskFailure]

object TaskFailureRepository {

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): TaskFailureRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new TaskFailureRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): TaskFailureRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new TaskFailureRepositoryImpl(persistenceStore)
  }
}

trait FrameworkIdRepository extends SingletonRepository[FrameworkId]

object FrameworkIdRepository {

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): FrameworkIdRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new FrameworkIdRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): FrameworkIdRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new FrameworkIdRepositoryImpl(persistenceStore)
  }
}

trait RuntimeConfigurationRepository extends SingletonRepository[RuntimeConfiguration]

object RuntimeConfigurationRepository {
  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): RuntimeConfigurationRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new RuntimeConfigurationRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): RuntimeConfigurationRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new RuntimeConfigurationRepositoryImpl(persistenceStore)
  }
}

class AppRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[AbsolutePathId, AppDefinition, C, K],
    marhaller: Marshaller[AppDefinition, S],
    unmarshaller: Unmarshaller[S, AppDefinition],
    ctx: ExecutionContext
) extends PersistenceStoreVersionedRepository[AbsolutePathId, AppDefinition, K, C, S](persistenceStore, _.id, _.version.toOffsetDateTime)
    with AppRepository {

  private[storage] var beforeStore = Option.empty[(AbsolutePathId, Option[OffsetDateTime]) => Future[Done]]

  override def store(v: AppDefinition): Future[Done] =
    async { // linter:ignore UnnecessaryElseBranch
      beforeStore match {
        case Some(preStore) =>
          await(preStore(v.id, None))
        case _ =>
      }
      await(super.store(v))
    }

  override def storeVersion(v: AppDefinition): Future[Done] =
    async { // linter:ignore UnnecessaryElseBranch
      beforeStore match {
        case Some(preStore) =>
          await(preStore(v.id, Some(v.version.toOffsetDateTime)))
        case _ =>
      }
      await(super.storeVersion(v))
    }

  private[storage] def deleteVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Done] = {
    persistenceStore.deleteVersion(id, version)
  }
}

class PodRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[AbsolutePathId, PodDefinition, C, K],
    marshaller: Marshaller[PodDefinition, S],
    unmarshaller: Unmarshaller[S, PodDefinition],
    ctx: ExecutionContext
) extends PersistenceStoreVersionedRepository[AbsolutePathId, PodDefinition, K, C, S](
      persistenceStore,
      _.id,
      _.version.toOffsetDateTime
    )
    with PodRepository {
  private[storage] var beforeStore = Option.empty[(AbsolutePathId, Option[OffsetDateTime]) => Future[Done]]

  override def store(v: PodDefinition): Future[Done] =
    async { // linter:ignore:UnnecessaryElseBranch
      beforeStore match {
        case Some(preStore) =>
          await(preStore(v.id, None))
        case _ =>
      }
      await(super.store(v))
    }

  override def storeVersion(v: PodDefinition): Future[Done] =
    async { // linter:ignore:UnnecessaryElseBranch
      beforeStore match {
        case Some(preStore) =>
          await(preStore(v.id, Some(v.version.toOffsetDateTime)))
        case _ =>
      }
      await(super.storeVersion(v))
    }

  private[storage] def deleteVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Done] = {
    persistenceStore.deleteVersion(id, version)
  }
}

class InstanceRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[Instance.Id, state.Instance, C, K],
    marshaller: Marshaller[state.Instance, S],
    unmarshaller: Unmarshaller[S, state.Instance]
) extends PersistenceStoreRepository[Instance.Id, state.Instance, K, C, S](persistenceStore, _.instanceId)
    with InstanceRepository

class TaskFailureRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[AbsolutePathId, TaskFailure, C, K],
    marshaller: Marshaller[TaskFailure, S],
    unmarshaller: Unmarshaller[S, TaskFailure]
) extends PersistenceStoreVersionedRepository[AbsolutePathId, TaskFailure, K, C, S](persistenceStore, _.appId, _.version.toOffsetDateTime)
    with TaskFailureRepository

class FrameworkIdRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[String, FrameworkId, C, K],
    marshaller: Marshaller[FrameworkId, S],
    unmarshaller: Unmarshaller[S, FrameworkId]
) extends FrameworkIdRepository {
  private val ID = "id"
  private val repo = new PersistenceStoreRepository[String, FrameworkId, K, C, S](persistenceStore, _ => ID)
  override def get(): Future[Option[FrameworkId]] = repo.get(ID)
  override def store(v: FrameworkId): Future[Done] = repo.store(v)
  override def delete(): Future[Done] = repo.delete(ID)
}

class RuntimeConfigurationRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[String, RuntimeConfiguration, C, K],
    marshaller: Marshaller[RuntimeConfiguration, S],
    unmarshaller: Unmarshaller[S, RuntimeConfiguration]
) extends RuntimeConfigurationRepository {
  private val ID = "id"
  private val repo = new PersistenceStoreRepository[String, RuntimeConfiguration, K, C, S](persistenceStore, _ => ID)
  override def get(): Future[Option[RuntimeConfiguration]] = repo.get(ID)
  override def store(v: RuntimeConfiguration): Future[Done] = repo.store(v)
  override def delete(): Future[Done] = repo.delete(ID)
}
