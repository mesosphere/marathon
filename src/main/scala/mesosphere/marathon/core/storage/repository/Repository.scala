package mesosphere.marathon.core.storage.repository

// scalastyle:off
import java.time.OffsetDateTime
import java.util.UUID

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.legacy.store.EntityStore
import mesosphere.marathon.core.storage.repository.impl.legacy.{ AppEntityRepository, DeploymentEntityRepository, GroupEntityRepository, TaskEntityRepository, TaskFailureEntityRepository }
import mesosphere.marathon.core.storage.repository.impl.{ AppRepositoryImpl, DeploymentRepositoryImpl, StoredGroupRepositoryImpl, TaskFailureRepositoryImpl, TaskRepositoryImpl }
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, PathId, TaskFailure }
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.Protos.{ TaskID, TaskState }

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

/**
  * A Repository of values (T) identified uniquely by (Id)
  */
trait ReadOnlyRepository[Id, T] {
  def ids(): Source[Id, NotUsed]
  def all(): Source[T, NotUsed]
  def get(id: Id): Future[Option[T]]
}

/**
  * @inheritdoc
  * Additionally allows for storage and deletion
  */
trait Repository[Id, T] extends ReadOnlyRepository[Id, T] {
  def store(v: T): Future[Done]
  def delete(id: Id): Future[Done]
}

/**
  * @inheritdoc
  * Additionally allows for reading versions from the repository
  */
trait ReadOnlyVersionedRepository[Id, T] extends ReadOnlyRepository[Id, T] {
  def versions(id: Id): Source[OffsetDateTime, NotUsed]
  def getVersion(id: Id, version: OffsetDateTime): Future[Option[T]]
}

/**
  * @inheritdoc
  * Allows writing versions to the repository.
  */
trait VersionedRepository[Id, T] extends ReadOnlyVersionedRepository[Id, T] with Repository[Id, T] {
  def storeVersion(v: T): Future[Done]
  // Removes _only_ the current value, leaving all history in place.
  def deleteCurrent(id: Id): Future[Done]
}

trait GroupRepository {
  def root(): Future[Group]
  def rootVersions(): Source[OffsetDateTime, NotUsed]
  def rootVersion(version: OffsetDateTime): Future[Option[Group]]
  def storeRoot(group: Group, updatedApps: Seq[AppDefinition], deletedApps: Seq[PathId]): Future[Done]
}

object GroupRepository {
  def legacyRepository(
    store: (String, () => Group) => EntityStore[Group],
    maxVersions: Int,
    appRepository: AppRepository)(implicit
    ctx: ExecutionContext,
    metrics: Metrics): GroupEntityRepository = {
    val entityStore = store("group:", () => Group.empty)
    new GroupEntityRepository(entityStore, maxVersions, appRepository)
  }

  def zkRepository(
    store: PersistenceStore[ZkId, String, ZkSerialized],
    appRepository: AppRepository, maxVersions: Int)(implicit
    ctx: ExecutionContext,
    mat: Materializer): GroupRepository = {
    import ZkStoreSerialization._
    implicit val idResolver = groupIdResolver(maxVersions)
    new StoredGroupRepositoryImpl(store, appRepository)
  }

  def inMemRepository(
    store: PersistenceStore[RamId, String, Identity],
    appRepository: AppRepository,
    maxVersions: Int)(implicit ctx: ExecutionContext, mat: Materializer): GroupRepository = {
    import InMemoryStoreSerialization._
    implicit val idResolver = groupResolver(maxVersions)
    new StoredGroupRepositoryImpl(store, appRepository)
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

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized], maxVersions: Int): AppRepository = {
    import ZkStoreSerialization._
    implicit def idResolver = appDefResolver(maxVersions)

    new AppRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity], maxVersions: Int): AppRepository = {
    import InMemoryStoreSerialization._
    implicit def idResolver = appDefResolver(maxVersions)
    new AppRepositoryImpl(persistenceStore)
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

  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): DeploymentRepository = {
    import ZkStoreSerialization._
    new DeploymentRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): DeploymentRepository = {
    import InMemoryStoreSerialization._
    new DeploymentRepositoryImpl(persistenceStore)
  }
}

trait TaskRepository extends Repository[Task.Id, Task] {
  def tasks(appId: PathId): Source[Task.Id, NotUsed] = {
    ids().filter(_.runSpecId == appId)
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
    import ZkStoreSerialization._
    new TaskRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): TaskRepository = {
    import InMemoryStoreSerialization._
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
    import ZkStoreSerialization._
    implicit val resolver = taskFailureResolver(1)
    new TaskFailureRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): TaskFailureRepository = {
    import InMemoryStoreSerialization._
    implicit val resolver = taskFailureResolver(1)
    new TaskFailureRepositoryImpl(persistenceStore)
  }
}
