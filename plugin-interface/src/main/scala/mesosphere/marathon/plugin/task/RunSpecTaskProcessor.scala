package mesosphere.marathon.plugin.task

import mesosphere.marathon.plugin.RunSpec
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.{ TaskGroupInfo, TaskInfo }

/**
  * RunSpecTaskProcessor mutates a Mesos task info given some app specification.
  */
trait RunSpecTaskProcessor extends Plugin {
  /**
    * Customize task info (launch a single task)
    * @param runSpec The related run specification
    * @param builder The builder to customize
    */
  def taskInfo(runSpec: RunSpec, builder: TaskInfo.Builder): Unit

  /**
    * Customize task group (launch a pod)
    * @param runSpec The related run specification
    * @param builder The builder to customize
    */
  def taskGroup(runSpec: RunSpec, builder: TaskGroupInfo.Builder): Unit
}

