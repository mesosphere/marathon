package mesosphere.marathon.core.task.update

import mesosphere.marathon.core.task.Task

/**
  * Enumeration defining the effect that an applied Mesos status update has to a task.
  */
sealed trait TaskUpdateEffect extends Product with Serializable

object TaskUpdateEffect {
  case object Noop extends TaskUpdateEffect
  case class Update(newState: Task) extends TaskUpdateEffect
  case class Expunge(task: Task) extends TaskUpdateEffect

  case class Failure(cause: Throwable) extends TaskUpdateEffect
  object Failure {
    def apply(message: String): Failure = Failure(new RuntimeException(message))
  }
}
