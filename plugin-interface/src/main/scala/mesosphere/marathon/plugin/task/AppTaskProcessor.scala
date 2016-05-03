package mesosphere.marathon.plugin.task

import mesosphere.marathon.plugin.AppDefinition
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.TaskInfo

/**
  * AppTaskProcessor mutates a Mesos task info given some app specification.
  */
trait AppTaskProcessor extends Plugin {
  def apply(appdef: AppDefinition, builder: TaskInfo.Builder): Unit
}

object AppTaskProcessor {
  def apply(f: (AppDefinition, TaskInfo.Builder) => Unit): AppTaskProcessor = new AppTaskProcessor {
    override def apply(app: AppDefinition, b: TaskInfo.Builder): Unit = f(app, b)
  }
}
