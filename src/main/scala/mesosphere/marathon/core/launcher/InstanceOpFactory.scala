package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.RunSpec
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

/** Infers which TaskOps to create for given run spec and offers. */
trait InstanceOpFactory {
  /**
    * @return a TaskOp if and only if the offer matches the run spec.
    */
  def buildTaskOp(request: InstanceOpFactory.Request): Option[InstanceOp]
}

object InstanceOpFactory {
  /**
    * @param runSpec the related run specification definition
    * @param offer the offer to match against
    * @param instanceMap a map of running tasks or reservations for the given run spec,
    *              needed to check constraints and handle resident tasks
    * @param additionalLaunches the number of additional launches that has been requested
    */
  case class Request(runSpec: RunSpec, offer: Mesos.Offer, instanceMap: Map[Instance.Id, Instance],
      additionalLaunches: Int) {
    def frameworkId: FrameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)
    def instances: Iterable[Instance] = instanceMap.values
    lazy val reserved: Iterable[Task.Reserved] = instances.flatMap(_.tasks).collect { case r: Task.Reserved => r }
    def hasWaitingReservations: Boolean = reserved.nonEmpty
    def numberOfWaitingReservations: Int = reserved.size
    def isForResidentRunSpec: Boolean = runSpec.residency.isDefined
  }

  object Request {
    def apply(runSpec: RunSpec, offer: Mesos.Offer,
      instances: Iterable[Instance], additionalLaunches: Int): Request = {
      new Request(runSpec, offer, Instance.instancesById(instances), additionalLaunches)
    }
  }
}
