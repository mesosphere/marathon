package mesosphere.marathon.state

import scala.collection.mutable

class TaskFailureRepository(store: PersistenceStore[TaskFailure], maxVersions: Option[Int] = Some(1)) {

  protected[this] val taskFailures = mutable.Map[PathId, TaskFailure]()

  def store(id: PathId, value: TaskFailure): Unit =
    synchronized { taskFailures(id) = value }

  def expunge(id: PathId): Unit =
    synchronized { taskFailures -= id }

  def current(id: PathId): Option[TaskFailure] = taskFailures.get(id)

}
