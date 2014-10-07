package mesosphere.marathon.state

import mesosphere.marathon.event.TaskFailureEvent

class TaskFailureEventRepository(val store: PersistenceStore[TaskFailureEvent], appRepo: AppRepository, val maxVersions: Option[Int] = None) { //extends EntityRepository[TaskFailureEvent] {

  val taskFailuresMap = scala.collection.mutable.Map[PathId, TaskFailureEvent]()

  def store(id: PathId, value: TaskFailureEvent) = {
    taskFailuresMap(id) = value
  }

  def expunge(id: PathId) = {
    taskFailuresMap -= id
  }

  def current(id: PathId) = {
    taskFailuresMap(id)
  }

}