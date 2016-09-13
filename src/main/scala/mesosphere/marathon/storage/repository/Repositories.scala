package mesosphere.marathon.storage.repository

// scalastyle:off
import java.time.OffsetDateTime
import java.util.UUID

import akka.actor.ActorRefFactory
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.core.storage.repository.impl.{ PersistenceStoreRepository, PersistenceStoreVersionedRepository }
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkSerialized }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.legacy._
import mesosphere.marathon.storage.repository.legacy.store.EntityStore
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ TaskID, TaskState }

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

trait GroupRepository {
  /** Fetch the root, returns an empty root if the root doesn't yet exist */
  def root(): Future[Group]
  /** List previous versions of the root */
  def rootVersions(): Source[OffsetDateTime, NotUsed]
  /** Fetch a previous version of the root */
  def rootVersion(version: OffsetDateTime): Future[Option[Group]]

  /**
    * Store the root, new/updated apps and delete apps. fails if it could not
    * update the apps or the root, but deletion errors are ignored.
    */
  def storeRoot(group: Group, updatedApps: Seq[AppDefinition], deletedApps: Seq[PathId],
    updatedPods: Seq[PodDefinition], deletedPods: Seq[PathId]): Future[Done]
}

object GroupRepository {
  def legacyRepository(
    store: (String, () => Group) => EntityStore[Group],
    maxVersions: Int,
    appRepository: AppRepository,
    podRepository: PodRepository)(implicit
    ctx: ExecutionContext,
    metrics: Metrics): GroupEntityRepository = {
    val entityStore = store("group:", () => Group.empty)
    new GroupEntityRepository(entityStore, maxVersions, appRepository, podRepository)
  }

  def zkRepository(
    store: PersistenceStore[ZkId, String, ZkSerialized],
    appRepository: AppRepository,
    podRepository: PodRepository)(implicit
    ctx: ExecutionContext,
    mat: Materializer): StoredGroupRepositoryImpl[ZkId, String, ZkSerialized] = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new StoredGroupRepositoryImpl(store, appRepository, podRepository)
  }

  def inMemRepository(
    store: PersistenceStore[RamId, String, Identity],
    appRepository: AppRepository,
    podRepository: PodRepository)(implicit
    ctx: ExecutionContext,
    mat: Materializer): StoredGroupRepositoryImpl[RamId, String, Identity] = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new StoredGroupRepositoryImpl(store, appRepository, podRepository)
  }
}

trait ReadOnlyAppRepository extends ReadOnlyVersionedRepository[PathId, AppDefinition]
trait AppRepository extends VersionedRepository[PathId, AppDefinition] with ReadOnlyAppRepository

object AppRepository {
  def legacyRepository(
    store: (String, () => AppDefinition) => EntityStore[AppDefinition],
    maxVersions: Int)(implicit ctx: ExecutionContext, metrics: Metrics): AppEntityRepository = {
    val entityStore = store("app:", () => AppDefinition.apply())
    new AppEntityRepository(entityStore, maxVersions)
  }

  def zkRepository(
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized])(implicit ctx: ExecutionContext): AppRepositoryImpl[ZkId, String, ZkSerialized] = { // scalastyle:off
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new AppRepositoryImpl(persistenceStore)
  }

  def inMemRepository(
    persistenceStore: PersistenceStore[RamId, String, Identity])(implicit ctx: ExecutionContext): AppRepositoryImpl[RamId, String, Identity] = { // scalastyle:off
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new AppRepositoryImpl(persistenceStore)
  }
}

trait ReadOnlyPodRepository extends ReadOnlyVersionedRepository[PathId, PodDefinition]
trait PodRepository extends VersionedRepository[PathId, PodDefinition] with ReadOnlyPodRepository

object PodRepository {
  def legacyRepository(
    store: (String, () => PodDefinition) => EntityStore[PodDefinition],
    maxVersions: Int)(implicit ctx: ExecutionContext, metrics: Metrics): PodEntityRepository = {
    val entityStore = store("pod:", () => PodDefinition.apply())
    new PodEntityRepository(entityStore, maxVersions)
  }

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
  def legacyRepository(store: (String, () => DeploymentPlan) => EntityStore[DeploymentPlan])(implicit
    ctx: ExecutionContext,
    metrics: Metrics): DeploymentEntityRepository = {
    val entityStore = store("deployment:", () => DeploymentPlan.empty)
    new DeploymentEntityRepository(entityStore)
  }

  def zkRepository(
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
    groupRepository: StoredGroupRepositoryImpl[ZkId, String, ZkSerialized],
    appRepository: AppRepositoryImpl[ZkId, String, ZkSerialized],
    podRepository: PodRepositoryImpl[ZkId, String, ZkSerialized],
    maxVersions: Int)(implicit
    ctx: ExecutionContext,
    actorRefFactory: ActorRefFactory,
    mat: Materializer,
    metrics: Metrics): DeploymentRepositoryImpl[ZkId, String, ZkSerialized] = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new DeploymentRepositoryImpl(persistenceStore, groupRepository, appRepository, podRepository, maxVersions)
  }

  def inMemRepository(
    persistenceStore: PersistenceStore[RamId, String, Identity],
    groupRepository: StoredGroupRepositoryImpl[RamId, String, Identity],
    appRepository: AppRepositoryImpl[RamId, String, Identity],
    podRepository: PodRepositoryImpl[RamId, String, Identity],
    maxVersions: Int)(implicit
    ctx: ExecutionContext,
    actorRefFactory: ActorRefFactory,
    mat: Materializer,
    metrics: Metrics): DeploymentRepositoryImpl[RamId, String, Identity] = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new DeploymentRepositoryImpl(persistenceStore, groupRepository, appRepository, podRepository, maxVersions)
  }
}

trait TaskRepository extends Repository[Task.Id, Task] {
  def tasks(appId: PathId): Source[Task.Id, NotUsed] = {
    ids().filter(_.runSpecId == appId)
  }
}

// TODO(PODS): add an implementation
trait InstanceRepository extends Repository[Instance.Id, Instance] {
  def instances(runSpecId: PathId): Source[Instance.Id, NotUsed] = {
    ids().filter(_.runSpecId == runSpecId)
  }
}

object TaskRepository {
  def legacyRepository(
    store: (String, () => MarathonTaskState) => EntityStore[MarathonTaskState])(implicit
    ctx: ExecutionContext,
    metrics: Metrics): TaskEntityRepository = {
    val entityStore = store(
      TaskEntityRepository.storePrefix,
      () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()))
    new TaskEntityRepository(entityStore)
  }

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): TaskRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new TaskRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): TaskRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new TaskRepositoryImpl(persistenceStore)
  }
}

trait TaskFailureRepository extends VersionedRepository[PathId, TaskFailure]

object TaskFailureRepository {
  def legacyRepository(
    store: (String, () => TaskFailure) => EntityStore[TaskFailure])(implicit
    ctx: ExecutionContext,
    metrics: Metrics): TaskFailureEntityRepository = {
    val entityStore = store("taskFailure:", () => TaskFailure(
      PathId.empty,
      TaskID.newBuilder().setValue("").build,
      TaskState.TASK_STAGING
    ))
    new TaskFailureEntityRepository(entityStore, 1)
  }

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
  def legacyRepository(store: (String, () => FrameworkId) => EntityStore[FrameworkId])(implicit
    ctx: ExecutionContext,
    metrics: Metrics): FrameworkIdEntityRepository = {
    val entityStore = store("framework:", () => FrameworkId(UUID.randomUUID().toString))
    new FrameworkIdEntityRepository(entityStore)
  }

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): FrameworkIdRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new FrameworkIdRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): FrameworkIdRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new FrameworkIdRepositoryImpl(persistenceStore)
  }
}

trait EventSubscribersRepository extends SingletonRepository[EventSubscribers]

object EventSubscribersRepository {
  def legacyRepository(store: (String, () => EventSubscribers) => EntityStore[EventSubscribers])(implicit
    ctx: ExecutionContext,
    metrics: Metrics): EventSubscribersEntityRepository = {
    val entityStore = store("events:", () => EventSubscribers(Set.empty[String]))
    new EventSubscribersEntityRepository(entityStore)
  }

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): EventSubscribersRepository = {
    import mesosphere.marathon.storage.store.ZkStoreSerialization._
    new EventSubscribersRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): EventSubscribersRepository = {
    import mesosphere.marathon.storage.store.InMemoryStoreSerialization._
    new EventSubscribersRepositoryImpl(persistenceStore)
  }
}

class AppRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[PathId, AppDefinition, C, K],
  marhaller: Marshaller[AppDefinition, S],
  unmarshaller: Unmarshaller[S, AppDefinition],
  ctx: ExecutionContext)
    extends PersistenceStoreVersionedRepository[PathId, AppDefinition, K, C, S](
      persistenceStore,
      _.id,
      _.version.toOffsetDateTime)
    with AppRepository {

  private[storage] var beforeStore = Option.empty[(PathId, Option[OffsetDateTime]) => Future[Done]]

  override def store(v: AppDefinition): Future[Done] = async {
    beforeStore match {
      case Some(preStore) =>
        await(preStore(v.id, None))
      case _ =>
    }
    await(super.store(v))
  }

  override def storeVersion(v: AppDefinition): Future[Done] = async {
    beforeStore match {
      case Some(preStore) =>
        await(preStore(v.id, Some(v.version.toOffsetDateTime)))
      case _ =>
    }
    await(super.storeVersion(v))
  }

  private[storage] def deleteVersion(id: PathId, version: OffsetDateTime): Future[Done] = {
    persistenceStore.deleteVersion(id, version)
  }
}

class PodRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[PathId, PodDefinition, C, K],
  marshaller: Marshaller[PodDefinition, S],
  unmarshaller: Unmarshaller[S, PodDefinition],
  ctx: ExecutionContext)
    extends PersistenceStoreVersionedRepository[PathId, PodDefinition, K, C, S](
      persistenceStore,
      _.id,
      _.version.toOffsetDateTime
    ) with PodRepository {
  private[storage] var beforeStore = Option.empty[(PathId, Option[OffsetDateTime]) => Future[Done]]

  override def store(v: PodDefinition): Future[Done] = async {
    beforeStore match {
      case Some(preStore) =>
        await(preStore(v.id, None))
      case _ =>
    }
    await(super.store(v))
  }

  override def storeVersion(v: PodDefinition): Future[Done] = async {
    beforeStore match {
      case Some(preStore) =>
        await(preStore(v.id, Some(v.version.toOffsetDateTime)))
      case _ =>
    }
    await(super.storeVersion(v))
  }

  private[storage] def deleteVersion(id: PathId, version: OffsetDateTime): Future[Done] = {
    persistenceStore.deleteVersion(id, version)
  }
}

class TaskRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[Task.Id, Task, C, K],
  marshaller: Marshaller[Task, S],
  unmarshaller: Unmarshaller[S, Task])
    extends PersistenceStoreRepository[Task.Id, Task, K, C, S](persistenceStore, _.taskId)
    with TaskRepository

class TaskFailureRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(
  implicit
  ir: IdResolver[PathId, TaskFailure, C, K],
  marshaller: Marshaller[TaskFailure, S],
  unmarshaller: Unmarshaller[S, TaskFailure]
) extends PersistenceStoreVersionedRepository[PathId, TaskFailure, K, C, S](
  persistenceStore,
  _.appId,
  _.version.toOffsetDateTime) with TaskFailureRepository

class FrameworkIdRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(
    implicit
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

class EventSubscribersRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(
    implicit
    ir: IdResolver[String, EventSubscribers, C, K],
    marshaller: Marshaller[EventSubscribers, S],
    unmarshaller: Unmarshaller[S, EventSubscribers]
) extends EventSubscribersRepository {
  private val ID = "id"
  private val repo = new PersistenceStoreRepository[String, EventSubscribers, K, C, S](persistenceStore, _ => ID)
  override def get(): Future[Option[EventSubscribers]] = repo.get(ID)
  override def store(v: EventSubscribers): Future[Done] = repo.store(v)
  override def delete(): Future[Done] = repo.delete(ID)
}
