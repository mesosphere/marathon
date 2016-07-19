package mesosphere.marathon.core.storage.repository

// scalastyle:off
import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.legacy.AppEntityRepository
import mesosphere.marathon.core.storage.repository.impl.legacy.store.EntityStore
import mesosphere.marathon.core.storage.repository.impl.{ AppRepositoryImpl, DeploymentRepositoryImpl, TaskFailureRepositoryImpl, TaskRepositoryImpl }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, PathId, TaskFailure }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

trait Repository[Id, T] {
  def ids(): Source[Id, NotUsed]
  def all(): Source[T, NotUsed]
  def get(id: Id): Future[Option[T]]
  def delete(id: Id): Future[Done]
  def store(v: T): Future[Done]
}

trait VersionedRepository[Id, T] extends Repository[Id, T] {
  def versions(id: Id): Source[OffsetDateTime, NotUsed]
  def getVersion(id: Id, version: OffsetDateTime): Future[Option[T]]
  def storeVersion(v: T): Future[Done]
}

trait AppRepository extends VersionedRepository[PathId, AppDefinition]

object AppRepository {
  def legacyRepository(
    store: EntityStore[AppDefinition],
    maxVersions: Int)(implicit ctx: ExecutionContext, metrics: Metrics): AppEntityRepository = {
    new AppEntityRepository(store, maxVersions)
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
