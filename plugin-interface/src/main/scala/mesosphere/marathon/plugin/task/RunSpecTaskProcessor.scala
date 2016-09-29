package mesosphere.marathon.plugin.task

import mesosphere.marathon.plugin.ApplicationSpec
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.TaskInfo

/**
  * RunSpecTaskProcessor mutates a Mesos task info given some app specification.
  */
trait RunSpecTaskProcessor extends Plugin {
  /**
    * Customize task info (launch a single task)
    * @param runSpec The related run specification
    * @param builder The builder to customize
    */
  def taskInfo(runSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit
}

object RunSpecTaskProcessor {
  val empty: RunSpecTaskProcessor = new RunSpecTaskProcessor {
    override def taskInfo(runSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {}
  }
}
