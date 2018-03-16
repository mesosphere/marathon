package mesosphere.marathon
package core.task.tracker

import akka.Done
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.concurrent.Future

/**
  * Handles the processing of InstanceUpdateOperations. These might originate from
  * * Creating an instance
  * * Updating an instance (due to a state change, a timeout, a mesos update)
  * * Expunging an instance
  */
trait InstanceStateOpProcessor {
  /** Process an InstanceUpdateOperation and propagate its result. */
  def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect]

  def revert(instance: Instance): Future[Done]

  def forceExpunge(instanceId: Instance.Id): Future[Done]

  def updateStatus(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, updateTime: Timestamp): Future[Done]

  def updateReservationTimeout(instanceId: Instance.Id): Future[Done]
}
