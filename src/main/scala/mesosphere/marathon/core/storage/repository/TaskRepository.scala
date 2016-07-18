package mesosphere.marathon.core.storage.repository

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.TaskRepositoryImpl
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

trait TaskRepository {
  def ids(): Source[Task.Id, NotUsed]

  def get(id: Task.Id): Future[Option[Task]]

  def store(id: Task.Id, task: Task): Future[Done]
  def store(task: Task): Future[Done] = store(task.taskId, task)

  def all(): Source[Task, NotUsed]

  def tasksKeys(appId: PathId): Source[Task.Id, NotUsed] = {
    ids().filter(_.runSpecId == appId)
  }

  def delete(id: Task.Id): Future[Done]
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
