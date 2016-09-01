package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launcher.InstanceOp
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
    newTask: Task.LaunchedEphemeral): InstanceOp.Launch = {

    assume(newTask.id.mesosTaskId == taskInfo.getTaskId, "marathon task id and mesos task id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    val stateOp = TaskStateOp.LaunchEphemeral(newTask)
    InstanceOp.Launch(taskInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchOnReservation(
    taskInfo: Mesos.TaskInfo,
    newTask: TaskStateOp.LaunchOnReservation,
    oldTask: Task.Reserved): InstanceOp.Launch = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    InstanceOp.Launch(taskInfo, newTask, Some(oldTask), createOperations)
  }

  def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    newTask: TaskStateOp.Reserve,
    resources: Iterable[Mesos.Resource],
    localVolumes: Iterable[LocalVolume],
    oldTask: Option[Task] = None): InstanceOp.ReserveAndCreateVolumes = {

    def createOperations = Seq(
      offerOperationFactory.reserve(frameworkId, newTask.taskId, resources),
      offerOperationFactory.createVolumes(frameworkId, newTask.taskId, localVolumes))

    InstanceOp.ReserveAndCreateVolumes(newTask, resources, localVolumes, createOperations)
  }
}
