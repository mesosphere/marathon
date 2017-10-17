package mesosphere.marathon
package core.matcher.base.util

import mesosphere.marathon.core.launcher.impl.ReservationLabels
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.marathon.state.DiskSource
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.Resource.ReservationInfo
import org.apache.mesos.{ Protos => Mesos }

class OfferOperationFactory(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] lazy val role: String = roleOpt match {
    case Some(value) => value
    case _ => throw WrongConfigurationException(
      "No role set. Set --mesos_role to enable using local volumes in Marathon.")
  }

  private[this] lazy val principal: String = principalOpt match {
    case Some(value) => value
    case _ => throw WrongConfigurationException(
      "No principal set. Set --mesos_authentication_principal to enable using local volumes in Marathon.")
  }

  /** Create a launch operation for the given taskInfo. */
  def launch(taskInfo: Mesos.TaskInfo): Mesos.Offer.Operation = {
    val launch = Mesos.Offer.Operation.Launch.newBuilder()
      .addTaskInfos(taskInfo)
      .build()

    Mesos.Offer.Operation.newBuilder()
      .setType(Mesos.Offer.Operation.Type.LAUNCH)
      .setLaunch(launch)
      .build()
  }

  def launch(executorInfo: Mesos.ExecutorInfo, groupInfo: Mesos.TaskGroupInfo): Mesos.Offer.Operation = {
    val launch = Mesos.Offer.Operation.LaunchGroup.newBuilder()
      .setExecutor(executorInfo)
      .setTaskGroup(groupInfo)
      .build()
    Mesos.Offer.Operation.newBuilder()
      .setType(Mesos.Offer.Operation.Type.LAUNCH_GROUP)
      .setLaunchGroup(launch)
      .build()
  }

  def reserve(reservationLabels: ReservationLabels, resources: Seq[Mesos.Resource]): //
  Mesos.Offer.Operation = {
    val reservedResources = resources.map { resource =>

      val reservation = ReservationInfo.newBuilder()
        .setLabels(reservationLabels.mesosLabels)
        .setPrincipal(principal)

      Mesos.Resource.newBuilder(resource)
        .setRole(role)
        .setReservation(reservation)
        .build()
    }

    val reserve = Mesos.Offer.Operation.Reserve.newBuilder()
      .addAllResources(reservedResources.asJava)
      .build()

    Mesos.Offer.Operation.newBuilder()
      .setType(Mesos.Offer.Operation.Type.RESERVE)
      .setReserve(reserve)
      .build()
  }

  def createVolumes(
    reservationLabels: ReservationLabels,
    localVolumes: Seq[(DiskSource, LocalVolume)]): Mesos.Offer.Operation = {

    val volumes: Seq[Mesos.Resource] = localVolumes.map {
      case (source, vol) =>
        val disk = {
          val persistence = Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(vol.id.idString)
          principalOpt.foreach(persistence.setPrincipal)

          val volume = Mesos.Volume.newBuilder()
            .setContainerPath(vol.persistentVolume.containerPath)
            .setMode(vol.persistentVolume.mode)

          val builder = Mesos.Resource.DiskInfo.newBuilder()
            .setPersistence(persistence)
            .setVolume(volume)
          source.asMesos.foreach(builder.setSource)
          builder
        }

        val reservation = Mesos.Resource.ReservationInfo.newBuilder()
          .setLabels(reservationLabels.mesosLabels)
        principalOpt.foreach(reservation.setPrincipal)

        Mesos.Resource.newBuilder()
          .setName("disk")
          .setType(Mesos.Value.Type.SCALAR)
          .setScalar(Mesos.Value.Scalar.newBuilder().setValue(vol.persistentVolume.persistent.size.toDouble).build())
          .setRole(role)
          .setReservation(reservation)
          .setDisk(disk)
          .build()
    }

    val create = Mesos.Offer.Operation.Create.newBuilder()
      .addAllVolumes(volumes.asJava)

    Mesos.Offer.Operation.newBuilder()
      .setType(Mesos.Offer.Operation.Type.CREATE)
      .setCreate(create)
      .build()
  }
}
