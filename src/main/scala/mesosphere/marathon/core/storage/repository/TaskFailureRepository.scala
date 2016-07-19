package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime

import akka.{ Done, NotUsed }
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, RamId, InMemoryStoreSerialization }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.TaskFailureRepositoryImpl
import mesosphere.marathon.state.{ PathId, TaskFailure }

import scala.concurrent.Future

trait TaskFailureRepository {
  def ids(): Source[PathId, NotUsed]

  def versions(id: PathId): Source[OffsetDateTime, NotUsed]

  def get(id: PathId): Future[Option[TaskFailure]]

  def get(id: PathId, version: OffsetDateTime): Future[Option[TaskFailure]]

  def store(id: PathId, value: TaskFailure): Future[Done]

  def delete(id: PathId): Future[Done]
}

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
