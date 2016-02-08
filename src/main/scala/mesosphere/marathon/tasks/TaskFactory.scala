package mesosphere.marathon.tasks

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.Protos.{ Offer, TaskInfo }

/** Create tasks from app definitions and offers. */
trait TaskFactory {
  import TaskFactory.CreatedTask

  /**
    * Return the corresponding task if and only if the offer matches the app.
    */
  def newTask(app: AppDefinition, offer: Offer, runningTasks: Iterable[Task]): Option[CreatedTask]
}

object TaskFactory {
  case class CreatedTask(mesosTask: TaskInfo, task: Task)
}

