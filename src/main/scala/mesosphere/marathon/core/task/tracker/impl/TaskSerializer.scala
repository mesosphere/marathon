package mesosphere.marathon
package core.task.tracker.impl

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.{ Task, TaskCondition }
import mesosphere.marathon.core.task.Task.{ LocalVolumeId, Reservation }
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.stream.Implicits._
import org.slf4j.LoggerFactory

/**
  * Converts between [[Task]] objects and their serialized representation MarathonTask.
  */
object TaskSerializer {

  private[this] val log = LoggerFactory.getLogger(getClass)

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

    def reservation: Option[Task.Reservation] =
      opt(_.hasReservation, _.getReservation).map(ReservationSerializer.fromProto)

    lazy val maybeAppVersion: Option[Timestamp] = opt(_.hasVersion, _.getVersion).map(Timestamp.apply)

    lazy val hostPorts = proto.getPortsList.map(_.intValue())(collection.breakOut)

    val mesosStatus = opt(_.hasStatus, _.getStatus)
    val hostName = required("host", opt(_.hasOBSOLETEHost, _.getOBSOLETEHost))
    val ipAddresses = mesosStatus.map(NetworkInfo.resolveIpAddresses).getOrElse(Nil)
    val networkInfo: NetworkInfo = NetworkInfo(hostName, hostPorts, ipAddresses)
    log.debug(s"Deserialized networkInfo: $networkInfo")

    val taskStatus = {
      Task.Status(
        stagedAt = Timestamp(proto.getStagedAt),
        startedAt = opt(_.hasStartedAt, _.getStartedAt).map(Timestamp.apply),
        mesosStatus = mesosStatus,
        condition = opt(
          // Invalid could also mean UNKNOWN since it's the default value of an enum
          t => t.hasCondition && t.getCondition != Protos.MarathonTask.Condition.Invalid,
          _.getCondition
        ).flatMap(TaskConditionSerializer.fromProto)
          // although this is an optional field, migration should have really taken care of this.
          // because of a bug in migration, some empties slipped through. so we make up for it here.
          .orElse(opt(_.hasStatus, _.getStatus).map(TaskCondition.apply))
          .getOrElse(Condition.Unknown),
        networkInfo = networkInfo
      )
    }

    constructTask(
      taskId = Task.Id(proto.getId),
      reservation,
      taskStatus,
      maybeAppVersion
    )
  }

  private[this] def constructTask(
    taskId: Task.Id,
    reservationOpt: Option[Reservation],
    taskStatus: Task.Status,
    maybeVersion: Option[Timestamp]): Task = {

    val runSpecVersion = maybeVersion.getOrElse {
      // we cannot default to something meaningful here because Reserved tasks have no runSpec version
      // the version for a reserved task will however not be considered and when a new task is launched,
      // it will be given the latest runSpec version
      log.warn(s"$taskId has no version. Defaulting to Timestamp.zero")
      Timestamp.zero
    }

    reservationOpt match {
      case Some(reservation) if taskStatus.condition == Condition.Reserved =>
        Task.Reserved(taskId, reservation, taskStatus, runSpecVersion)

      case Some(reservation) =>
        Task.LaunchedOnReservation(taskId, runSpecVersion, taskStatus, reservation)

      case None if taskStatus.condition != Condition.Reserved =>
        Task.LaunchedEphemeral(taskId, runSpecVersion, taskStatus)

      case _ =>
        val msg = s"Unable to deserialize task $taskId ($reservationOpt, $taskStatus, $maybeVersion). It is neither reserved nor launched"
        throw SerializationFailedException(msg)
    }
  }

  def toProto(task: Task): Protos.MarathonTask = {
    val builder = Protos.MarathonTask.newBuilder()

    def setId(taskId: Task.Id): Unit = builder.setId(taskId.idString)
    def setReservation(reservation: Task.Reservation): Unit = {
      builder.setReservation(ReservationSerializer.toProto(reservation))
    }
    def setLaunched(status: Task.Status, hostPorts: Seq[Int]): Unit = {
      builder.setStagedAt(status.stagedAt.millis)
      status.startedAt.foreach(startedAt => builder.setStartedAt(startedAt.millis))
      status.mesosStatus.foreach(status => builder.setStatus(status))
      builder.addAllPorts(hostPorts.map(Integer.valueOf))
    }
    def setVersion(appVersion: Timestamp): Unit = {
      builder.setVersion(appVersion.toString)
    }
    def setTaskCondition(condition: Condition): Unit = {
      builder.setCondition(TaskConditionSerializer.toProto(condition))
    }
    // this is needed for unit tests; need to be able to serialize deprecated fields and verify
    // they're deserialized correctly
    def setNetworkInfo(networkInfo: NetworkInfo): Unit = {
      builder.setOBSOLETEHost(networkInfo.hostName)
    }

    setId(task.taskId)
    setTaskCondition(task.status.condition)
    setNetworkInfo(task.status.networkInfo)
    setVersion(task.runSpecVersion)

    task match {
      case launched: Task.LaunchedEphemeral =>
        setLaunched(launched.status, task.status.networkInfo.hostPorts)

      case reserved: Task.Reserved =>
        setReservation(reserved.reservation)

      case launchedOnR: Task.LaunchedOnReservation =>
        setLaunched(launchedOnR.status, task.status.networkInfo.hostPorts)
        setReservation(launchedOnR.reservation)
    }

    builder.build()
  }
}

object TaskConditionSerializer {

  import mesosphere._
  import mesosphere.marathon.core.condition.Condition._

  private val proto2model = Map(
    marathon.Protos.MarathonTask.Condition.Reserved -> Reserved,
    marathon.Protos.MarathonTask.Condition.Created -> Created,
    marathon.Protos.MarathonTask.Condition.Error -> Error,
    marathon.Protos.MarathonTask.Condition.Failed -> Failed,
    marathon.Protos.MarathonTask.Condition.Finished -> Finished,
    marathon.Protos.MarathonTask.Condition.Killed -> Killed,
    marathon.Protos.MarathonTask.Condition.Killing -> Killing,
    marathon.Protos.MarathonTask.Condition.Running -> Running,
    marathon.Protos.MarathonTask.Condition.Staging -> Staging,
    marathon.Protos.MarathonTask.Condition.Starting -> Starting,
    marathon.Protos.MarathonTask.Condition.Unreachable -> Unreachable,
    marathon.Protos.MarathonTask.Condition.Gone -> Gone,
    marathon.Protos.MarathonTask.Condition.Unknown -> Unknown,
    marathon.Protos.MarathonTask.Condition.Dropped -> Dropped
  )

  private val model2proto: Map[Condition, marathon.Protos.MarathonTask.Condition] =
    proto2model.map(_.swap)

  def fromProto(proto: Protos.MarathonTask.Condition): Option[Condition] = {
    proto2model.get(proto)
  }

  def toProto(taskCondition: Condition): Protos.MarathonTask.Condition = {
    model2proto.getOrElse(
      taskCondition,
      throw SerializationFailedException(s"Unable to serialize $taskCondition"))
  }
}

private[marathon] object ReservationSerializer {

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
        .setInitiated(timeout.initiated.millis)
        .setDeadline(timeout.deadline.millis)
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
    val volumes: Seq[LocalVolumeId] = proto.getLocalVolumeIdsList.map {
      case LocalVolumeId(volumeId) => volumeId
      case invalid: String => throw SerializationFailedException(s"$invalid is no valid volumeId")
    }(collection.breakOut)

    Reservation(volumes, state)
  }

  def toProto(reservation: Task.Reservation): Protos.MarathonTask.Reservation = {
    Protos.MarathonTask.Reservation.newBuilder()
      .addAllLocalVolumeIds(reservation.volumeIds.map(_.idString))
      .setState(StateSerializer.toProto(reservation.state))
      .build()
  }
}
