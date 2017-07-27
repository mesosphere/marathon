package mesosphere.marathon
package core.instance.update

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation._
import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Maps a [[InstanceUpdateOperation]] to the appropriate [[InstanceUpdateEffect]].
  *
  * @param directInstanceTracker an InstanceTracker that is routed directly to the underlying implementation
  *                          without going through the WhenLeaderActor indirection.
  */
private[marathon] class InstanceUpdateOpResolver(
    directInstanceTracker: InstanceTracker,
    clock: Clock) extends StrictLogging {

  private[this] val updater = InstanceUpdater

  /**
    * Depending on the type of [[InstanceUpdateOperation]], this will verify that the instance
    * exists (if the operation must be applied to an instance), or that the instance does not
    * yet exist (if the operation effectively creates a new instance). If this prerequisite is
    * violated the future will fail with an [[IllegalStateException]], otherwise the operation
    * will be applied and result in an [[InstanceUpdateEffect]].
    */
  def resolve(op: InstanceUpdateOperation)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    op match {
      case op: LaunchEphemeral =>
        createInstance(op.instanceId)(updater.launchEphemeral(op, clock.now()))

      case op: LaunchOnReservation =>
        updateExistingInstance(op.instanceId)(updater.launchOnReservation(_, op))

      case op: MesosUpdate =>
        updateExistingInstance(op.instanceId)(updater.mesosUpdate(_, op))

      case op: ReservationTimeout =>
        updateExistingInstance(op.instanceId)(updater.reservationTimeout(_, clock.now()))

      case op: Reserve =>
        createInstance(op.instanceId)(updater.reserve(op, clock.now()))

      case op: ForceExpunge =>
        directInstanceTracker.instance(op.instanceId).map {
          case Some(existingInstance) =>
            updater.forceExpunge(existingInstance, clock.now())

          case None =>
            InstanceUpdateEffect.Noop(op.instanceId)
        }

      case op: Revert =>
        Future.successful(updater.revert(op.instance))
    }
  }

  /**
    * Helper method that verifies that an instance already exists. If it does, it will apply the given function and
    * return the resulting effect; otherwise this will result in a. [[InstanceUpdateEffect.Failure]].
    * @param id ID of the instance that is expected to exist.
    * @param applyOperation the operation that shall be applied to the instance
    * @return The [[InstanceUpdateEffect]] that results from applying the given operation.
    */
  private[this] def updateExistingInstance(id: Instance.Id)(applyOperation: Instance => InstanceUpdateEffect)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    directInstanceTracker.instance(id).map {
      case Some(existingInstance) =>
        applyOperation(existingInstance)

      case None =>
        InstanceUpdateEffect.Failure(
          new IllegalStateException(s"$id of app [${id.runSpecId}] does not exist"))
    }
  }

  /**
    * Helper method that verifies that no instance with the given ID exists, and applies the given operation if that is
    * true. If an instance with this ID already exists, this will result in an [[InstanceUpdateEffect.Failure]].
    * @param id ID of the instance that shall be created.
    * @param applyOperation the operation that will create the instance.
    * @return The [[InstanceUpdateEffect]] that results from applying the given operation.
    */
  private[this] def createInstance(id: Instance.Id)(applyOperation: => InstanceUpdateEffect)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    directInstanceTracker.instance(id).map {
      case Some(_) =>
        InstanceUpdateEffect.Failure(
          new IllegalStateException(s"$id of app [${id.runSpecId}] already exists"))

      case None =>
        applyOperation
    }
  }

}
