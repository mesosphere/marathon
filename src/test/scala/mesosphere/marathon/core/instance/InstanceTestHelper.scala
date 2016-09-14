package mesosphere.marathon.core.instance

import mesosphere.marathon.core.task.Task

import scala.collection.immutable.Seq

trait InstanceTestHelper {

  def instancesFor(tasks: Seq[Task]): Seq[Instance] = tasks.map(Instance(_))(collection.breakOut)
}

object InstanceTestHelper extends InstanceTestHelper
