package mesosphere.marathon.core.launcher

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.mesosphere.usi.core.models.commands.{ExpungePod, KillPod, LaunchPod, SchedulerCommand}
import com.mesosphere.usi.core.models.template.{LaunchGroupRunTemplate, LegacyLaunchRunTemplate, RunTemplate}
import com.mesosphere.usi.core.models.{PodId, StateEvent, TaskName, TaskBuilder => UsiTaskBuilder}
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted, InstanceUpdated}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.{ApplicationSpec, PodSpec}
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.state.AppDefinition
import mesosphere.mesos.{TaskBuilder => MarathonTaskBuilder}
import org.apache.mesos.{Protos => Mesos}

case class UsiAdapter(
  instanceTracker: InstanceTracker,
  config: MarathonConf,
  pluginManager: PluginManager = PluginManager.None) {

  private[this] lazy val runSpecTaskProc: RunSpecTaskProcessor = combine(
    pluginManager.plugins[RunSpecTaskProcessor].toIndexedSeq)

  def combine(processors: Seq[RunSpecTaskProcessor]): RunSpecTaskProcessor = new RunSpecTaskProcessor {
    override def taskInfo(runSpec: ApplicationSpec, builder: Mesos.TaskInfo.Builder): Unit = {
      processors.foreach(_.taskInfo(runSpec, builder))
    }
    override def taskGroup(podSpec: PodSpec, executor: Mesos.ExecutorInfo.Builder, taskGroup: Mesos.TaskGroupInfo.Builder): Unit = {
      processors.foreach(_.taskGroup(podSpec, executor, taskGroup))
    }
  }

  /**
    * Adapter from Marathon TaskBuilder to USI TaskBuilder.
    */
  class TaskBuilderAdapter(app: AppDefinition, taskId: Task.Id) extends UsiTaskBuilder {
    val taskBuilder = new MarathonTaskBuilder(app, taskId, config, runSpecTaskProc)

    override  def buildTask(
      builder: Mesos.TaskInfo.Builder,
      matchedOffer: Mesos.Offer,
      taskResources: Seq[Mesos.Resource],
      peerTaskResources: Map[TaskName, Seq[Mesos.Resource]]): Unit = {
      taskResources.map { resource =>
        resource.getType

      }
      taskBuilder.build(matchedOffer, taskResources, None, true)
    }
  }

  def templateFrom(instance: Instance): RunTemplate = {
    instance.runSpec match {
      case _: PodDefinition => ???
      case app: AppDefinition =>
        val taskId = instance.tasksMap.headOption match {
          case Some((id, _)) => Task.Id.nextIncarnationFor(id)
          case None => Task.Id(instance.instanceId)
        }
        val taskBuilder = new TaskBuilderAdapter(app, taskId)
        new LegacyLaunchRunTemplate(app.role, taskBuilder)
    }
  }

  val instanceUpdatesToSchedulerCommands: Flow[InstanceChange, SchedulerCommand, NotUsed] =
    Flow[InstanceChange].map {
      case InstanceUpdated(newInstance, None, _) =>
        LaunchPod(PodId(newInstance.instanceId.idString), templateFrom(newInstance))
      case InstanceUpdated(newInstance, Some(oldInstance), _) =>
        if(newInstance.state.goal != Goal.Running) {
          KillPod(PodId(newInstance.instanceId.idString))
        } else {
          ???
        }
      case InstanceDeleted(instance, _, _) =>
        ExpungePod(PodId(instance.instanceId.idString))
      case other => ???
    }


  val schedulerFlow: Flow[SchedulerCommand, StateEvent, NotUsed] = ???

  instanceTracker.instanceUpdates.flatMapConcat { case (_, updates) => updates }
    .via(instanceUpdatesToSchedulerCommands)
    .via(schedulerFlow)
}
