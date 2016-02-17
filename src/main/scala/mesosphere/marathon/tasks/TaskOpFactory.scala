package mesosphere.marathon.tasks

import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolume
import org.apache.mesos.{ Protos => Mesos }

class TaskOpFactory(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(principalOpt, roleOpt)

  def launch(
    taskInfo: Mesos.TaskInfo,
    newTask: Task,
    oldTask: Option[Task] = None): TaskOp.Launch = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    TaskOp.Launch(taskInfo, newTask, oldTask, createOperations)
  }

  def reserveAndCreateVolumes(
    newTask: Task,
    resources: Iterable[Mesos.Resource],
    localVolumes: Iterable[LocalVolume],
    oldTask: Option[Task] = None): TaskOp.ReserveAndCreateVolumes = {

    def createOperations = Seq(
      offerOperationFactory.reserve(resources),
      offerOperationFactory.createVolumes(newTask.appId, localVolumes))

    TaskOp.ReserveAndCreateVolumes(newTask, resources, localVolumes, oldTask, createOperations)
  }
}
