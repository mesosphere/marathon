package mesosphere.marathon
package core.matcher.base.util

import mesosphere.marathon.core.launcher.InstanceOpFactory
import mesosphere.marathon.core.launcher.impl.ReservationLabels
import mesosphere.marathon.metrics.{Metrics, ServiceMetric}
import mesosphere.marathon.state.VolumeMount
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.ResourceProviderID
import org.apache.mesos.Protos.Resource.ReservationInfo
import org.apache.mesos.{Protos => Mesos}

class OfferOperationFactory(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val launchOperationCountMetric = Metrics.counter(ServiceMetric, getClass, "launchOperationCount")
  private[this] val launchGroupOperationCountMetric = Metrics.counter(ServiceMetric, getClass, "launchGroupOperationCount")
  private[this] val reserveOperationCountMetric = Metrics.counter(ServiceMetric, getClass, "reserveOperationCount")

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

    launchOperationCountMetric.increment()
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

    launchGroupOperationCountMetric.increment()
    Mesos.Offer.Operation.newBuilder()
      .setType(Mesos.Offer.Operation.Type.LAUNCH_GROUP)
      .setLaunchGroup(launch)
      .build()
  }

  def reserve(reservationLabels: ReservationLabels, resources: Seq[Mesos.Resource]): Seq[Mesos.Offer.Operation] = {

    // Mesos requires that there is an operation per each resource provider ID
    resources.groupBy(ResourceProviderID.fromResourceProto).values.map { resources =>
      val reservedResources = resources.map { resource =>

        val reservation = ReservationInfo.newBuilder()
          .setLabels(reservationLabels.mesosLabels)
          .setPrincipal(principal)

        Mesos.Resource.newBuilder(resource)
          .setRole(role)
          .setReservation(reservation)
          .build(): @silent
      }

      val reserve = Mesos.Offer.Operation.Reserve.newBuilder()
        .addAllResources(reservedResources.asJava)
        .build()

      reserveOperationCountMetric.increment()
      Mesos.Offer.Operation.newBuilder()
        .setType(Mesos.Offer.Operation.Type.RESERVE)
        .setReserve(reserve)
        .build()
    }.to[Seq]
  }

  def createVolumes(
    reservationLabels: ReservationLabels,
    localVolumes: Seq[InstanceOpFactory.OfferedVolume]): Seq[Mesos.Offer.Operation] = {

    // Mesos requires that there is an operation per each resource provider ID
    localVolumes.groupBy(_.providerId).values.map { localVolumes =>
      val volumes: Seq[Mesos.Resource] = localVolumes.map {
        case InstanceOpFactory.OfferedVolume(providerId, source, vol) =>
          val disk = {
            val persistence = Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(vol.id.idString)
            principalOpt.foreach(persistence.setPrincipal)

            // Pod volumes have names, whereas app volumes do not. Since pod persistent volumes are created
            // in the executor container, a persistent volume name is used as its name on the Mesos end. In this case
            // all the corresponding volume mounts refer to the volume by its name. On the other hand, in case of apps,
            // volumes do not have names, and since persistent volumes are not shared in this case, the mount path
            // is used instead.
            val name = vol.persistentVolume.name.getOrElse(vol.mount.mountPath)

            val mode = VolumeMount.readOnlyToProto(vol.mount.readOnly)
            val volume = Mesos.Volume.newBuilder()
              .setContainerPath(name)
              .setMode(mode)

            val builder = Mesos.Resource.DiskInfo.newBuilder()
              .setPersistence(persistence)
              .setVolume(volume)
            source.asMesos.foreach(builder.setSource)
            builder
          }

          val reservation = Mesos.Resource.ReservationInfo.newBuilder()
            .setLabels(reservationLabels.mesosLabels)
          principalOpt.foreach(reservation.setPrincipal)

          val builder = Mesos.Resource.newBuilder()
            .setName("disk")
            .setType(Mesos.Value.Type.SCALAR)
            .setScalar(Mesos.Value.Scalar.newBuilder().setValue(vol.persistentVolume.persistent.size.toDouble).build())
            .setRole(role)
            .setReservation(reservation)
            .setDisk(disk): @silent

          providerId.foreach { providerId =>
            val providerIdProto = Mesos.ResourceProviderID.newBuilder().setValue(providerId.value).build()
            builder.setProviderId(providerIdProto)
          }

          builder.build()
      }

      val create = Mesos.Offer.Operation.Create.newBuilder()
        .addAllVolumes(volumes.asJava)

      Mesos.Offer.Operation.newBuilder()
        .setType(Mesos.Offer.Operation.Type.CREATE)
        .setCreate(create)
        .build()
    }.to[Seq]
  }
}
