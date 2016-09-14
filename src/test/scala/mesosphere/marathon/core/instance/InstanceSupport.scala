package mesosphere.marathon.core.instance

import mesosphere.marathon.core.task.Task

import scala.collection.immutable.Seq

trait InstanceSupport {

  def instancesFor(tasks: Seq[Task]): Seq[Instance] = tasks.map(Instance(_))(collection.breakOut)
}

object InstanceSupport extends InstanceSupport
