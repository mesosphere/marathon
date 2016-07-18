package mesosphere.marathon.core.storage.repository

import akka.{ Done, NotUsed }
import akka.stream.scaladsl.Source
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.TaskRepositoryImpl
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

trait TaskRepository {
  def ids(): Source[String, NotUsed]

  def get(id: String): Future[Option[MarathonTask]]

  def store(task: MarathonTask): Future[Done]

  def all(): Source[MarathonTask, NotUsed]

  def tasksKeys(appId: PathId): Source[String, NotUsed] = {
    ids().filter(name => name.startsWith(appId.safePath))
  }

  def delete(id: String): Future[Done]
}

object TaskRepository {
  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): TaskRepository =
    new TaskRepositoryImpl(persistenceStore)(
      ZkStoreSerialization.taskResolver,
      ZkStoreSerialization.taskMarshaller,
      ZkStoreSerialization.taskUnmarshaller
    )

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): TaskRepository =
    new TaskRepositoryImpl(persistenceStore)(
      InMemoryStoreSerialization.taskResolver,
      InMemoryStoreSerialization.marshaller,
      InMemoryStoreSerialization.unmarshaller
    )
}
