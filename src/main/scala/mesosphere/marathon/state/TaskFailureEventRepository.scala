package mesosphere.marathon.state

class TaskFailureEventRepository(val store: PersistenceStore[TaskFailureEvent], val maxVersions: Option[Int] = None) { //extends EntityRepository[TaskFailureEvent] {

  val taskFailuresMap = scala.collection.mutable.Map[PathId, TaskFailureEvent]()

  def store(id: PathId, value: TaskFailureEvent) = {
    taskFailuresMap(id) = value
  }

  def expunge(id: PathId) = {
    taskFailuresMap -= id
  }

  def current(id: PathId): Option[TaskFailureEvent] = {
    if (taskFailuresMap.contains(id)) Some(taskFailuresMap(id)) else None
  }

}