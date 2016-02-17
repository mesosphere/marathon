package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.{ SerializationFailedException, Protos }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ LocalVolumeId, ReservationWithVolumes }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * Converts between [[Task]] objects and their serialized representation MarathonTask.
  */
object TaskSerializer {
  import scala.collection.JavaConverters._

  def fromProto(proto: Protos.MarathonTask): Task = {

    def required[T](name: String, maybeValue: Option[T]): T = {
      maybeValue.getOrElse(throw new IllegalArgumentException(s"task[${proto.getId}]: $name must be set"))
    }

    def opt[T](
      hasAttribute: Protos.MarathonTask => Boolean, getAttribute: Protos.MarathonTask => T): Option[T] = {

      if (hasAttribute(proto)) {
        Some(getAttribute(proto))
      }
      else {
        None
      }
    }

    def agentInfo: Task.AgentInfo = {
      Task.AgentInfo(
        host = required("host", opt(_.hasHost, _.getHost)),
        agentId = opt(_.hasSlaveId, _.getSlaveId).map(_.getValue),
        attributes = proto.getAttributesList.iterator().asScala.toVector
      )
    }

    def reservationWithVolume: Option[Task.ReservationWithVolumes] = {
      if (proto.hasReservationWithVolumes) {
        Some(ReservationWithVolumes(
          proto.getReservationWithVolumes.getLocalVolumeIdsList.asScala.map {
            case LocalVolumeId(volumeId) => volumeId
            case invalid                 => throw new SerializationFailedException(s"$invalid is no valid volumeId")
          }))
      }
      else {
        None
      }
    }

    def launchedTask: Option[Task.Launched] = {
      if (proto.hasStagedAt) {
        Some(
          Task.Launched(
            appVersion = Timestamp(proto.getVersion),
            status = Task.Status(
              stagedAt = Timestamp(proto.getStagedAt),
              startedAt = if (proto.hasStartedAt) Some(Timestamp(proto.getStartedAt)) else None,
              mesosStatus = opt(_.hasStatus, _.getStatus)
            ),
            networking = if (proto.getPortsCount != 0) {
              Task.HostPorts(proto.getPortsList.iterator().asScala.map(_.intValue()).toVector)
            }
            else if (proto.getNetworksCount != 0) {
              Task.NetworkInfoList(proto.getNetworksList.asScala)
            }
            else {
              Task.NoNetworking
            }
          )
        )
      }
      else {
        None
      }
    }

    Task(
      taskId = Task.Id(proto.getId),
      agentInfo = agentInfo,
      reservationWithVolumes = reservationWithVolume,
      launched = launchedTask
    )
  }

  def toProto(taskState: Task): Protos.MarathonTask = {
    val builder = Protos.MarathonTask.newBuilder()

    builder.setId(taskState.taskId.idString)

    import taskState.agentInfo
    builder.setHost(agentInfo.host)
    agentInfo.agentId.foreach { agentId =>
      builder.setSlaveId(MesosProtos.SlaveID.newBuilder().setValue(agentId))
    }
    builder.addAllAttributes(agentInfo.attributes.asJava)

    taskState.reservationWithVolumes.foreach {
      reservation =>
        builder.setReservationWithVolumes(
          Protos.MarathonTask.ReservationWithVolumes.newBuilder()
            .addAllLocalVolumeIds(reservation.volumeIds.map(_.idString).asJava)
            .build())
    }

    taskState.launched.foreach { launchedTask =>
      builder.setVersion(launchedTask.appVersion.toString)
      builder.setStagedAt(launchedTask.status.stagedAt.toDateTime.getMillis)
      launchedTask.status.startedAt.foreach(startedAt => builder.setStartedAt(startedAt.toDateTime.getMillis))
      launchedTask.status.mesosStatus.foreach(status => builder.setStatus(status))
      launchedTask.networking match {
        case Task.HostPorts(hostPorts) =>
          builder.addAllPorts(hostPorts.view.map(Integer.valueOf(_)).asJava)
        case Task.NetworkInfoList(networkInfoList) =>
          builder.addAllNetworks(networkInfoList.asJava)
        case Task.NoNetworking => // nothing
      }

    }

    builder.build()
  }

}
