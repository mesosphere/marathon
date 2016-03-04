package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ LocalVolumeId, ReservationWithVolumes }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.{ Protos, SerializationFailedException }
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

    def reservation: Option[Task.ReservationWithVolumes] = {
      if (proto.hasReservation) {
        Some(ReservationWithVolumes(
          proto.getReservation.getLocalVolumeIdsList.asScala.map {
            case LocalVolumeId(volumeId) => volumeId
            case invalid: String         => throw new SerializationFailedException(s"$invalid is no valid volumeId")
          }))
      }
      else {
        None
      }
    }

    def reservedState: Option[Task.Reserved.State] = {
      if (proto.hasReservedState) {
        Some(ReservedStateSerializer.fromProto(proto.getReservedState))
      }
      else None
    }

    def appVersion = Timestamp(proto.getVersion)

    def taskStatus = Task.Status(
      stagedAt = Timestamp(proto.getStagedAt),
      startedAt = if (proto.hasStartedAt) Some(Timestamp(proto.getStartedAt)) else None,
      mesosStatus = opt(_.hasStatus, _.getStatus)
    )

    def networking = if (proto.getPortsCount != 0) {
      Task.HostPorts(proto.getPortsList.iterator().asScala.map(_.intValue()).toVector)
    }
    else if (proto.getNetworksCount != 0) {
      Task.NetworkInfoList(proto.getNetworksList.asScala)
    }
    else {
      Task.NoNetworking
    }

    def launchedTask: Option[Task.Launched] = {
      if (proto.hasStagedAt) {
        Some(
          Task.Launched(
            appVersion = appVersion,
            status = taskStatus,
            networking = networking
          )
        )
      }
      else {
        None
      }
    }

    constructTask(
      taskId = Task.Id(proto.getId),
      agentInfo = agentInfo,
      reservation,
      reservedState,
      launchedTask
    )
  }

  private[this] def constructTask(
    taskId: Task.Id,
    agentInfo: Task.AgentInfo,
    reservationOpt: Option[ReservationWithVolumes],
    reservedStateOpt: Option[Task.Reserved.State],
    launchedOpt: Option[Task.Launched]): Task = {

    (taskId, agentInfo, reservationOpt, reservedStateOpt, launchedOpt) match {

      case (_, _, Some(reservation), None, Some(launched)) =>
        Task.LaunchedOnReservation(
          taskId, agentInfo, launched.appVersion, launched.status, launched.networking, reservation)

      case (_, _, Some(reservation), Some(reservedState), None) =>
        Task.Reserved(
          taskId, agentInfo, reservedState, reservation)

      case (_, _, None, None, Some(launched)) =>
        Task.LaunchedEphemeral(
          taskId, agentInfo, launched.appVersion, launched.status, launched.networking)

      case (_, _, _, _, _) =>
        val msg = s"Unable to deserialize task $taskId, agentInfo=$agentInfo, reservation=$reservationOpt," +
          s"reservedState=$reservedStateOpt, launched=$launchedOpt"
        throw new SerializationFailedException(msg)
    }
  }

  def toProto(task: Task): Protos.MarathonTask = {
    val builder = Protos.MarathonTask.newBuilder()

    def setId(taskId: Task.Id): Unit = builder.setId(taskId.idString)
    def setAgentInfo(agentInfo: Task.AgentInfo): Unit = {
      builder.setHost(agentInfo.host)
      agentInfo.agentId.foreach { agentId =>
        builder.setSlaveId(MesosProtos.SlaveID.newBuilder().setValue(agentId))
      }
      builder.addAllAttributes(agentInfo.attributes.asJava)
    }
    def setReservation(reservation: Task.ReservationWithVolumes): Unit = {
      builder.setReservation(
        Protos.MarathonTask.Reservation.newBuilder()
          .addAllLocalVolumeIds(reservation.volumeIds.map(_.idString).asJava)
          .build())
    }
    def setReservedState(state: Task.Reserved.State): Unit = {
      builder.setReservedState(ReservedStateSerializer.toProto(state))
    }
    def setLaunched(appVersion: Timestamp, status: Task.Status, networking: Task.Networking): Unit = {
      builder.setVersion(appVersion.toString)
      builder.setStagedAt(status.stagedAt.toDateTime.getMillis)
      status.startedAt.foreach(startedAt => builder.setStartedAt(startedAt.toDateTime.getMillis))
      status.mesosStatus.foreach(status => builder.setStatus(status))
      networking match {
        case Task.HostPorts(hostPorts) =>
          builder.addAllPorts(hostPorts.view.map(Integer.valueOf(_)).asJava)
        case Task.NetworkInfoList(networkInfoList) =>
          builder.addAllNetworks(networkInfoList.asJava)
        case Task.NoNetworking => // nothing
      }
    }

    setId(task.taskId)
    setAgentInfo(task.agentInfo)

    task match {
      case launched: Task.LaunchedEphemeral =>
        setLaunched(launched.appVersion, launched.status, launched.networking)

      case reserved: Task.Reserved =>
        setReservation(reserved.reservation)
        setReservedState(reserved.state)

      case launchedOnR: Task.LaunchedOnReservation =>
        setLaunched(launchedOnR.appVersion, launchedOnR.status, launchedOnR.networking)
        setReservation(launchedOnR.reservation)
    }

    builder.build()
  }
}

object ReservedStateSerializer {

  object TimeoutSerializer {
    def fromProto(proto: Protos.MarathonTask.ReservedState.Timeout): Task.Reserved.Timeout = {
      val reason: Task.Reserved.Timeout.Reason = proto.getReason match {
        case Protos.MarathonTask.ReservedState.Timeout.Reason.RelaunchEscalationTimeout =>
          Task.Reserved.Timeout.Reason.RelaunchEscalationTimeout
        case Protos.MarathonTask.ReservedState.Timeout.Reason.ReservationTimeout =>
          Task.Reserved.Timeout.Reason.ReservationTimeout
        case _ => throw new SerializationFailedException(s"Unable to parse ${proto.getReason}")
      }

      Task.Reserved.Timeout(
        Timestamp(proto.getInitiated),
        Timestamp(proto.getDeadline),
        reason
      )
    }

    def toProto(timeout: Task.Reserved.Timeout): Protos.MarathonTask.ReservedState.Timeout = {
      val reason = timeout.reason match {
        case Task.Reserved.Timeout.Reason.RelaunchEscalationTimeout =>
          Protos.MarathonTask.ReservedState.Timeout.Reason.RelaunchEscalationTimeout
        case Task.Reserved.Timeout.Reason.ReservationTimeout =>
          Protos.MarathonTask.ReservedState.Timeout.Reason.ReservationTimeout
      }
      Protos.MarathonTask.ReservedState.Timeout.newBuilder()
        .setInitiated(timeout.initiated.toDateTime.getMillis)
        .setDeadline(timeout.deadline.toDateTime.getMillis)
        .setReason(reason)
        .build()
    }
  }

  def fromProto(proto: Protos.MarathonTask.ReservedState): Task.Reserved.State = {
    val timeout = if (proto.hasTimeout) Some(TimeoutSerializer.fromProto(proto.getTimeout)) else None
    proto.getType match {
      case Protos.MarathonTask.ReservedState.Type.New => Task.Reserved.State.New(timeout)
      case Protos.MarathonTask.ReservedState.Type.Suspended => Task.Reserved.State.Suspended(timeout)
      case Protos.MarathonTask.ReservedState.Type.Garbage => Task.Reserved.State.Garbage(timeout)
      case Protos.MarathonTask.ReservedState.Type.Unknown => Task.Reserved.State.Unknown(timeout)
      case _ => throw new SerializationFailedException(s"Unable to parse ${proto.getType}")
    }
  }

  def toProto(state: Task.Reserved.State): Protos.MarathonTask.ReservedState = {
    val builder = Protos.MarathonTask.ReservedState.newBuilder()
    val stateType = state match {
      case Task.Reserved.State.New(_)       => Protos.MarathonTask.ReservedState.Type.New
      case Task.Reserved.State.Suspended(_) => Protos.MarathonTask.ReservedState.Type.Suspended
      case Task.Reserved.State.Garbage(_)   => Protos.MarathonTask.ReservedState.Type.Garbage
      case Task.Reserved.State.Unknown(_)   => Protos.MarathonTask.ReservedState.Type.Unknown
    }

    builder.setType(stateType)
    state.timeout.foreach { timeout => builder.setTimeout(TimeoutSerializer.toProto(timeout)) }

    builder.build()
  }
}
