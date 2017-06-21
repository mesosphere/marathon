package mesosphere.marathon
package plugin.task

import mesosphere.marathon.plugin.{ ApplicationSpec, PodSpec }
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.{ ExecutorInfo, TaskGroupInfo, TaskInfo }

/**
  * RunSpecTaskProcessor mutates a Mesos task info given some app specification.
  */
trait RunSpecTaskProcessor extends Plugin {
  /**
    * Customize task info (launch a single task)
    *
    * @param appSpec The related run specification
    * @param builder The builder to customize
    */
  def taskInfo(appSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit

  /**
    * Customize task group (launch a pod)
    *
    * @param podSpec The related run specification
    * @param executor The ExecutorInfo to customize
    * @param taskGroup The TaskGroupInfo to customize
    */
  def taskGroup(podSpec: PodSpec, executor: ExecutorInfo.Builder, taskGroup: TaskGroupInfo.Builder): Unit
}

object RunSpecTaskProcessor {
  val empty: RunSpecTaskProcessor = new RunSpecTaskProcessor {
    override def taskInfo(runSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {}
    override def taskGroup(podSpec: PodSpec, exec: ExecutorInfo.Builder, taskGroup: TaskGroupInfo.Builder): Unit = {}
  }
}

