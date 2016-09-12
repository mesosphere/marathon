package mesosphere.marathon.core.task

sealed trait TaskStateChange

object TaskStateChange {
  case class Update(newState: Task, oldState: Option[Task]) extends TaskStateChange
  case class Expunge(task: Task) extends TaskStateChange
  case class NoChange(taskId: Task.Id) extends TaskStateChange
  case class Failure(cause: Throwable) extends TaskStateChange
  object Failure {
    def apply(message: String): Failure = Failure(TaskStateChangeException(message))
  }
}

object EffectiveTaskStateChange {
  import TaskStateChange._

  def unapply(stateChange: TaskStateChange): Option[Task] = stateChange match {
    case Update(newState, _) => Some(newState)
    case Expunge(oldState) => Some(oldState)
    case _ => None
  }
}

case class TaskStateChangeException(message: String) extends Exception(message)
