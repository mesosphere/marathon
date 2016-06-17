package mesosphere.marathon.plugin.task

import mesosphere.marathon.plugin.RunSpec
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.TaskInfo

/**
  * RunSpecTaskProcessor mutates a Mesos task info given some app specification.
  */
trait RunSpecTaskProcessor extends Plugin {
  def apply(runSpec: RunSpec, builder: TaskInfo.Builder): Unit
}

object RunSpecTaskProcessor {
  def apply(f: (RunSpec, TaskInfo.Builder) => Unit): RunSpecTaskProcessor = new RunSpecTaskProcessor {
    override def apply(app: RunSpec, b: TaskInfo.Builder): Unit = f(app, b)
  }
}
