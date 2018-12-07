package mesosphere.marathon
package core.launcher.impl

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory}
import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.metrics.Metrics
import org.apache.mesos.{Protos => Mesos}

class InstanceOpFactoryHelper(
    private val metrics: Metrics,
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(metrics, principalOpt, roleOpt)

  def provision(
    taskInfo: Mesos.TaskInfo,
    stateOp: InstanceUpdateOperation.Provision): InstanceOp.LaunchTask = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    InstanceOp.LaunchTask(taskInfo, stateOp, oldInstance = None, createOperations)
  }

  def provision(
    executorInfo: Mesos.ExecutorInfo,
    groupInfo: Mesos.TaskGroupInfo,
    instanceId: Instance.Id,
    stateOp: InstanceUpdateOperation.Provision): InstanceOp.LaunchTaskGroup = {

    assume(
      executorInfo.getExecutorId.getValue == instanceId.executorIdString,
      "marathon pod instance id and mesos executor id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(executorInfo, groupInfo))

    InstanceOp.LaunchTaskGroup(executorInfo, groupInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchOnReservation(
    taskInfo: Mesos.TaskInfo,
    newState: InstanceUpdateOperation.Provision,
    oldState: Instance): InstanceOp.LaunchTask = {

    assume(
      oldState.hasReservation,
      "only an instance with a reservation can be re-launched")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    InstanceOp.LaunchTask(taskInfo, newState, Some(oldState), createOperations)
  }

  def launchOnReservation(
    executorInfo: Mesos.ExecutorInfo,
    groupInfo: Mesos.TaskGroupInfo,
    newState: InstanceUpdateOperation.Provision,
    oldState: Instance): InstanceOp.LaunchTaskGroup = {

    def createOperations = Seq(offerOperationFactory.launch(executorInfo, groupInfo))

    InstanceOp.LaunchTaskGroup(executorInfo, groupInfo, newState, Some(oldState), createOperations)
  }

  /**
    * Returns a set of operations to reserve ALL resources (cpu, mem, ports, disk, etc.) and then create persistent
    * volumes against them as needed
    */
  def reserveAndCreateVolumes(
    reservationLabels: ReservationLabels,
    newState: InstanceUpdateOperation.Reserve,
    resources: Seq[Mesos.Resource],
    localVolumes: Seq[InstanceOpFactory.OfferedVolume]): InstanceOp.ReserveAndCreateVolumes = {

    def createOperations =
      offerOperationFactory.reserve(reservationLabels, resources) ++
        offerOperationFactory.createVolumes(reservationLabels, localVolumes)

    InstanceOp.ReserveAndCreateVolumes(newState, resources, createOperations)
  }
}
