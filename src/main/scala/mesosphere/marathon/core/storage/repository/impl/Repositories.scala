package mesosphere.marathon.core.storage.repository.impl

// scalastyle:off
import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, PathId, TaskFailure }
import mesosphere.util.state.FrameworkId

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

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