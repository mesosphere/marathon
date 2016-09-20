package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ LocalVolumeId, Reservation }
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
      } else {
        None
      }
    }

    def agentInfo: Instance.AgentInfo = {
      Instance.AgentInfo(
        host = required("host", opt(_.hasHost, _.getHost)),
        agentId = opt(_.hasSlaveId, _.getSlaveId).map(_.getValue),
        attributes = proto.getAttributesList.iterator().asScala.toVector
      )
    }

    def reservation: Option[Task.Reservation] = if (proto.hasReservation) {
      Some(ReservationSerializer.fromProto(proto.getReservation))
    } else None

    def appVersion = Timestamp(proto.getVersion)

    val taskStatus = Task.Status(
      stagedAt = Timestamp(proto.getStagedAt),
      startedAt = if (proto.hasStartedAt) Some(Timestamp(proto.getStartedAt)) else None,
      mesosStatus = opt(_.hasStatus, _.getStatus),
      taskStatus = MarathonTaskStatusSerializer.fromProto(proto.getMarathonTaskStatus)
    )

    def hostPorts = proto.getPortsList.iterator().asScala.map(_.intValue()).toVector

    def launchedTask: Option[Task.Launched] = {
      if (proto.hasStagedAt) {
        Some(
          Task.Launched(
            runSpecVersion = appVersion,
            status = taskStatus,
            hostPorts = hostPorts
          )
        )
      } else {
        None
      }
    }

    constructTask(
      taskId = Task.Id(proto.getId),
      agentInfo = agentInfo,
      reservation,
      launchedTask,
      taskStatus
    )
  }

  private[this] def constructTask(
    taskId: Task.Id,
    agentInfo: Instance.AgentInfo,
    reservationOpt: Option[Reservation],
    launchedOpt: Option[Task.Launched],
    taskStatus: Task.Status): Task = {

    (reservationOpt, launchedOpt) match {

      case (Some(reservation), Some(launched)) =>
        Task.LaunchedOnReservation(
          taskId, agentInfo, launched.runSpecVersion, launched.status, launched.hostPorts, reservation)

      case (Some(reservation), None) =>
        Task.Reserved(taskId, agentInfo, reservation, taskStatus)

      case (None, Some(launched)) =>
        Task.LaunchedEphemeral(
          taskId, agentInfo, launched.runSpecVersion, launched.status, launched.hostPorts)

      case (None, None) =>
        val msg = s"Unable to deserialize task $taskId, agentInfo=$agentInfo. It is neither reserved nor launched"
        throw SerializationFailedException(msg)
    }
  }

  def toProto(task: Task): Protos.MarathonTask = {
    val builder = Protos.MarathonTask.newBuilder()

    def setId(taskId: Task.Id): Unit = builder.setId(taskId.idString)
    def setAgentInfo(agentInfo: Instance.AgentInfo): Unit = {
      builder.setHost(agentInfo.host)
      agentInfo.agentId.foreach { agentId =>
        builder.setSlaveId(MesosProtos.SlaveID.newBuilder().setValue(agentId))
      }
      builder.addAllAttributes(agentInfo.attributes.asJava)
    }
    def setReservation(reservation: Task.Reservation): Unit = {
      builder.setReservation(ReservationSerializer.toProto(reservation))
    }
    def setLaunched(appVersion: Timestamp, status: Task.Status, hostPorts: Seq[Int]): Unit = {
      builder.setVersion(appVersion.toString)
      builder.setStagedAt(status.stagedAt.toDateTime.getMillis)
      status.startedAt.foreach(startedAt => builder.setStartedAt(startedAt.toDateTime.getMillis))
      status.mesosStatus.foreach(status => builder.setStatus(status))
      builder.addAllPorts(hostPorts.map(Integer.valueOf).asJava)
    }
    def setMarathonTaskStatus(marathonTaskStatus: InstanceStatus): Unit = {
      builder.setMarathonTaskStatus(MarathonTaskStatusSerializer.toProto(marathonTaskStatus))
    }

    setId(task.taskId)
    setAgentInfo(task.agentInfo)
    setMarathonTaskStatus(task.status.taskStatus)

    task match {
      case launched: Task.LaunchedEphemeral =>
        setLaunched(launched.runSpecVersion, launched.status, launched.hostPorts)

      case reserved: Task.Reserved =>
        setReservation(reserved.reservation)

      case launchedOnR: Task.LaunchedOnReservation =>
        setLaunched(launchedOnR.runSpecVersion, launchedOnR.status, launchedOnR.hostPorts)
        setReservation(launchedOnR.reservation)
    }

    builder.build()
  }
}

object MarathonTaskStatusSerializer {

  import mesosphere.marathon.core.instance.InstanceStatus._
  import mesosphere._

  private val proto2model = Map(
    marathon.Protos.MarathonTask.MarathonTaskStatus.Reserved -> Reserved,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Created -> Created,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Error -> Error,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Failed -> Failed,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Finished -> Finished,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Killed -> Killed,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Killing -> Killing,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Running -> Running,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Staging -> Staging,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Starting -> Starting,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Unreachable -> Unreachable,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Gone -> Gone,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Unknown -> Unknown,
    marathon.Protos.MarathonTask.MarathonTaskStatus.Dropped -> Dropped
  )

  private val model2proto: Map[InstanceStatus, marathon.Protos.MarathonTask.MarathonTaskStatus] =
    proto2model.map(_.swap)

  def fromProto(proto: Protos.MarathonTask.MarathonTaskStatus): InstanceStatus = {
    proto2model.getOrElse(proto, throw SerializationFailedException(s"Unable to parse $proto"))
  }

  def toProto(marathonTaskStatus: InstanceStatus): Protos.MarathonTask.MarathonTaskStatus = {
    model2proto.getOrElse(
      marathonTaskStatus,
      throw SerializationFailedException(s"Unable to serialize $marathonTaskStatus"))
  }
}

private[impl] object ReservationSerializer {
  import scala.collection.JavaConverters._

  object TimeoutSerializer {
    import Protos.MarathonTask.Reservation.State.{ Timeout => ProtoTimeout }
    import Task.Reservation.Timeout
    def fromProto(proto: ProtoTimeout): Timeout = {
      val reason: Timeout.Reason = proto.getReason match {
        case ProtoTimeout.Reason.RelaunchEscalationTimeout => Timeout.Reason.RelaunchEscalationTimeout
        case ProtoTimeout.Reason.ReservationTimeout => Timeout.Reason.ReservationTimeout
        case _ => throw SerializationFailedException(s"Unable to parse ${proto.getReason}")
      }

      Timeout(
        Timestamp(proto.getInitiated),
        Timestamp(proto.getDeadline),
        reason
      )
    }

    def toProto(timeout: Timeout): ProtoTimeout = {
      val reason = timeout.reason match {
        case Timeout.Reason.RelaunchEscalationTimeout => ProtoTimeout.Reason.RelaunchEscalationTimeout
        case Timeout.Reason.ReservationTimeout => ProtoTimeout.Reason.ReservationTimeout
      }
      ProtoTimeout.newBuilder()
        .setInitiated(timeout.initiated.toDateTime.getMillis)
        .setDeadline(timeout.deadline.toDateTime.getMillis)
        .setReason(reason)
        .build()
    }
  }

  object StateSerializer {
    import Protos.MarathonTask.Reservation.{ State => ProtoState }
    import Task.Reservation.State

    def fromProto(proto: ProtoState): State = {
      val timeout = if (proto.hasTimeout) Some(TimeoutSerializer.fromProto(proto.getTimeout)) else None
      proto.getType match {
        case ProtoState.Type.New => State.New(timeout)
        case ProtoState.Type.Launched => State.Launched
        case ProtoState.Type.Suspended => State.Suspended(timeout)
        case ProtoState.Type.Garbage => State.Garbage(timeout)
        case ProtoState.Type.Unknown => State.Unknown(timeout)
        case _ => throw SerializationFailedException(s"Unable to parse ${proto.getType}")
      }
    }

    def toProto(state: Task.Reservation.State): ProtoState = {
      val stateType = state match {
        case Task.Reservation.State.New(_) => Protos.MarathonTask.Reservation.State.Type.New
        case Task.Reservation.State.Launched => Protos.MarathonTask.Reservation.State.Type.Launched
        case Task.Reservation.State.Suspended(_) => Protos.MarathonTask.Reservation.State.Type.Suspended
        case Task.Reservation.State.Garbage(_) => Protos.MarathonTask.Reservation.State.Type.Garbage
        case Task.Reservation.State.Unknown(_) => Protos.MarathonTask.Reservation.State.Type.Unknown
      }
      val builder = Protos.MarathonTask.Reservation.State.newBuilder()
        .setType(stateType)
      state.timeout.foreach(timeout => builder.setTimeout(TimeoutSerializer.toProto(timeout)))
      builder.build()
    }
  }

  def fromProto(proto: Protos.MarathonTask.Reservation): Task.Reservation = {
    if (!proto.hasState) throw SerializationFailedException(s"Serialized resident task has no state: $proto")

    val state: Task.Reservation.State = StateSerializer.fromProto(proto.getState)
    val volumes = proto.getLocalVolumeIdsList.asScala.map {
      case LocalVolumeId(volumeId) => volumeId
      case invalid: String => throw SerializationFailedException(s"$invalid is no valid volumeId")
    }

    Reservation(volumes, state)
  }

  def toProto(reservation: Task.Reservation): Protos.MarathonTask.Reservation = {
    Protos.MarathonTask.Reservation.newBuilder()
      .addAllLocalVolumeIds(reservation.volumeIds.map(_.idString).asJava)
      .setState(StateSerializer.toProto(reservation.state))
      .build()
  }
}
