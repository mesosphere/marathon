package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.Protos.{ TaskInfo, Offer }

/** Create tasks from app definitions and offers. */
trait TaskFactory {
  import TaskFactory.CreatedTask

  /**
    * Return the corresponding task if and only if the offer matches the app.
    */
  def newTask(app: AppDefinition, offer: Offer, runningTasks: Set[MarathonTask]): Option[CreatedTask]
}

object TaskFactory {
  case class CreatedTask(mesosTask: TaskInfo, marathonTask: MarathonTask)
}

