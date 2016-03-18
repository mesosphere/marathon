package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.core.task.{ TaskStateOp, Task }
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

class TaskOpFactoryHelper(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(principalOpt, roleOpt)

  def launchEphemeral(
    taskInfo: Mesos.TaskInfo,
    newTask: Task.LaunchedEphemeral): TaskOp.Launch = {

    assume(newTask.taskId.mesosTaskId == taskInfo.getTaskId, "marathon task id and mesos task id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    val stateOp = TaskStateOp.LaunchEphemeral(newTask)
    TaskOp.Launch(taskInfo, stateOp, oldTask = None, createOperations)
  }

  def launchOnReservation(
    taskInfo: Mesos.TaskInfo,
    newTask: TaskStateOp.LaunchOnReservation,
    oldTask: Task.Reserved): TaskOp.Launch = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    TaskOp.Launch(taskInfo, newTask, Some(oldTask), createOperations)
  }

  def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    newTask: TaskStateOp.Reserve,
    resources: Iterable[Mesos.Resource],
    localVolumes: Iterable[LocalVolume],
    oldTask: Option[Task] = None): TaskOp.ReserveAndCreateVolumes = {

    def createOperations = Seq(
      offerOperationFactory.reserve(frameworkId, newTask.taskId, resources),
      offerOperationFactory.createVolumes(frameworkId, newTask.taskId, localVolumes))

    TaskOp.ReserveAndCreateVolumes(newTask, resources, localVolumes, createOperations)
  }
}
