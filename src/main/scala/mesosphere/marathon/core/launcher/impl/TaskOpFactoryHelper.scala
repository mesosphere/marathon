package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

class TaskOpFactoryHelper(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(principalOpt, roleOpt)

  def launch(
    taskInfo: Mesos.TaskInfo,
    newTask: Task,
    oldTask: Option[Task] = None): TaskOp.Launch = {

    assume(newTask.taskId.mesosTaskId == taskInfo.getTaskId, "marathon task id and mesos task id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    TaskOp.Launch(taskInfo, newTask, oldTask, createOperations)
  }

  def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    newTask: Task,
    resources: Iterable[Mesos.Resource],
    localVolumes: Iterable[LocalVolume],
    oldTask: Option[Task] = None): TaskOp.ReserveAndCreateVolumes = {

    def createOperations = Seq(
      offerOperationFactory.reserve(frameworkId, newTask.taskId, resources),
      offerOperationFactory.createVolumes(frameworkId, newTask.taskId, localVolumes))

    TaskOp.ReserveAndCreateVolumes(newTask, resources, localVolumes, oldTask, createOperations)
  }
}
