package mesosphere.marathon.core.matcher.base.util

import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.Resource.ReservationInfo
import org.apache.mesos.{ Protos => Mesos }

class OfferOperationFactory(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] lazy val role: String = roleOpt match {
    case Some(value) => value
    case _ => throw new WrongConfigurationException(
      "No role set. Set --mesos_role to enable using local volumes in Marathon.")
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

  def reserve(frameworkId: FrameworkId, taskId: Task.Id, resources: Iterable[Mesos.Resource]): Mesos.Offer.Operation = {
    import scala.collection.JavaConverters._
    val reservedResources = resources.map { resource =>

      val reservation = ReservationInfo.newBuilder()
        .setLabels(TaskLabels.labelsForTask(frameworkId, taskId).mesosLabels)
      principalOpt.foreach(reservation.setPrincipal)

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
    frameworkId: FrameworkId,
    taskId: Task.Id,
    localVolumes: Iterable[LocalVolume]): Mesos.Offer.Operation = {
    import scala.collection.JavaConverters._

    val volumes: Iterable[Mesos.Resource] = localVolumes.map { vol =>
      val disk = {
        val persistence = Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(vol.id.idString)
        principalOpt.foreach(persistence.setPrincipal)

        val volume = Mesos.Volume.newBuilder()
          .setContainerPath(vol.persistentVolume.containerPath)
          .setMode(vol.persistentVolume.mode)

        Mesos.Resource.DiskInfo.newBuilder()
          .setPersistence(persistence)
          .setVolume(volume)
      }

      val reservation = Mesos.Resource.ReservationInfo.newBuilder()
        .setLabels(TaskLabels.labelsForTask(frameworkId, taskId).mesosLabels)
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
