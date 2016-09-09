package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.core.pod.{ PodInstance, PodInstanceStateOp }
import mesosphere.marathon.core.task.{ InstanceStateOp, Task }
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

class InstanceOpFactoryHelper(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(principalOpt, roleOpt)

  def launchEphemeral(
    taskInfo: Mesos.TaskInfo,
    newTask: Task.LaunchedEphemeral): InstanceOp.LaunchTask = {

    assume(newTask.taskId.mesosTaskId == taskInfo.getTaskId, "marathon task id and mesos task id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    val stateOp = InstanceStateOp.LaunchEphemeral(Instance(newTask))
    InstanceOp.LaunchTask(taskInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchEphemeral(
    executorInfo: Mesos.ExecutorInfo,
    groupInfo: Mesos.TaskGroupInfo,
    newPodInstance: PodInstance.LaunchedEphemeral): InstanceOp.LaunchTaskGroup = {

    assume(executorInfo.getExecutorId == newPodInstance.id.mesosExecutorId,
      "marathon pod instance id and mesos executor id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(executorInfo, groupInfo))

    val stateOp = PodInstanceStateOp.LaunchedEphemeral(newPodInstance)
    InstanceOp.LaunchTaskGroup(executorInfo, groupInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchOnReservation(
    taskInfo: Mesos.TaskInfo,
    newTask: InstanceStateOp.LaunchOnReservation,
    oldTask: Task.Reserved): InstanceOp.LaunchTask = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    InstanceOp.LaunchTask(taskInfo, newTask, Some(Instance(oldTask)), createOperations)
  }

  def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    newTask: InstanceStateOp.Reserve,
    resources: Iterable[Mesos.Resource],
    localVolumes: Iterable[LocalVolume],
    oldTask: Option[Task] = None): InstanceOp.ReserveAndCreateVolumes = {

    def createOperations = Seq(
      offerOperationFactory.reserve(frameworkId, newTask.instanceId, resources),
      offerOperationFactory.createVolumes(frameworkId, newTask.instanceId, localVolumes))

    InstanceOp.ReserveAndCreateVolumes(newTask, resources, localVolumes, createOperations)
  }
}
