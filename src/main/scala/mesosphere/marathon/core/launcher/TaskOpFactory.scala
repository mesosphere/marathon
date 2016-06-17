package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.RunSpec
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

/** Infers which TaskOps to create for given run spec and offers. */
trait TaskOpFactory {
  /**
    * @return a TaskOp if and only if the offer matches the run spec.
    */
  def buildTaskOp(request: TaskOpFactory.Request): Option[TaskOp]
}

object TaskOpFactory {
  /**
    * @param runSpec the related run specification definition
    * @param offer the offer to match against
    * @param taskMap a map of running tasks or reservations for the given run spec,
    *              needed to check constraints and handle resident tasks
    * @param additionalLaunches the number of additional launches that has been requested
    */
  case class Request(runSpec: RunSpec, offer: Mesos.Offer, taskMap: Map[Task.Id, Task], additionalLaunches: Int) {
    def frameworkId: FrameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)
    def tasks: Iterable[Task] = taskMap.values
    lazy val reserved: Iterable[Task.Reserved] = tasks.collect { case r: Task.Reserved => r }
    def hasWaitingReservations: Boolean = reserved.nonEmpty
    def numberOfWaitingReservations: Int = reserved.size
    def isForResidentRunSpec: Boolean = runSpec.isResident
  }

  object Request {
    def apply(runSpec: RunSpec, offer: Mesos.Offer, tasks: Iterable[Task], additionalLaunches: Int): Request = {
      new Request(runSpec, offer, Task.tasksById(tasks), additionalLaunches)
    }
  }
}
