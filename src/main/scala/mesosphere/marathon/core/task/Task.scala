package mesosphere.marathon.core.task

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.state.{ AppDefinition, PathId, PersistentVolume, Timestamp }
import org.apache.mesos.Protos.TaskState
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory

//scalastyle:off number.of.types
/**
  * The state for launching a task. This might be a launched task or a reservation for launching a task or both.
  */
sealed trait Task {
  def taskId: Task.Id
  def agentInfo: Task.AgentInfo
  def reservationWithVolumes: Option[Task.Reservation]
  def launched: Option[Task.Launched]

  /** update the task based on the given trigger - depending on its state the task will decide what should happen */
  def update(update: TaskStateOp): TaskStateChange

  def appId: PathId = taskId.appId

  /**
    * Legacy conversion to MarathonTask. Cache result to speed up repeated uses.
    * Should be removed before releasing 0.16.
    */
  lazy val marathonTask: MarathonTask = TaskSerializer.toProto(this)

  def launchedMesosId: Option[MesosProtos.TaskID] = launched.map { _ =>
    // it doesn't make sense for an unlaunched task
    taskId.mesosTaskId
  }

  def mesosStatus: Option[MesosProtos.TaskStatus] = {
    launched.flatMap(_.status.mesosStatus).orElse {
      launchedMesosId.map { mesosId =>
        val taskStatusBuilder = MesosProtos.TaskStatus.newBuilder
          .setState(TaskState.TASK_STAGING)
          .setTaskId(mesosId)

        agentInfo.agentId.foreach { slaveId =>
          taskStatusBuilder.setSlaveId(MesosProtos.SlaveID.newBuilder().setValue(slaveId))
        }

        taskStatusBuilder.build()
      }
    }
  }

  def ipAddresses: Iterable[MesosProtos.NetworkInfo.IPAddress] = launched.map(_.ipAddresses).getOrElse(Iterable.empty)

  def effectiveIpAddress(app: AppDefinition): String = {
    val maybeContainerIp: Option[String] = ipAddresses.map(_.getIpAddress).headOption

    maybeContainerIp match {
      case Some(ipAddress) if app.ipAddress.isDefined => ipAddress
      case _ => agentInfo.host
    }
  }
}

object Task {
  /**
    * A LaunchedEphemeral task is a stateless task that does not consume reserved resources or persistent volumes.
    */
  case class LaunchedEphemeral(
      taskId: Task.Id,
      agentInfo: AgentInfo,
      appVersion: Timestamp,
      status: Status,
      networking: Networking) extends Task {

    import LaunchedEphemeral.log

    override def reservationWithVolumes: Option[Reservation] = None

    override def launched: Option[Launched] = Some(Task.Launched(appVersion, status, networking))

    private[this] def hasStartedRunning: Boolean = status.startedAt.isDefined

    //scalastyle:off cyclomatic.complexity method.length
    override def update(update: TaskStateOp): TaskStateChange = update match {
      // case 1: now running
      case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.Running(mesosStatus), now) if !hasStartedRunning =>
        val updated = copy(
          status = status.copy(
            startedAt = Some(now),
            mesosStatus = mesosStatus))
        TaskStateChange.Update(updated, Some(this))

      // case 2: terminal
      case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.Terminal(_), now) =>
        TaskStateChange.Expunge(this)

      // case 3: health or state updated
      case TaskStateOp.MesosUpdate(_, taskStatus, now) =>
        updatedHealthOrState(status.mesosStatus, taskStatus.mesosStatus).map { newStatus =>
          val updatedTask = copy(status = status.copy(mesosStatus = Some(newStatus)))
          TaskStateChange.Update(updatedTask, Some(this))
        } getOrElse {
          log.debug("Ignoring status update for {}. Status did not change.", taskId)
          TaskStateChange.NoChange(taskId)
        }

      case TaskStateOp.ForceExpunge(_) =>
        TaskStateChange.Expunge(this)

      // FIXME (3221): If the task needs to recreated after a not-accepted taskOp, Create is used.
      // that's neither nice nor obvious, so this should be improved
      case TaskStateOp.Create(task) =>
        TaskStateChange.Update(task, None)

      case _: TaskStateOp.LaunchOnReservation =>
        TaskStateChange.Failure("LaunchOnReservation on LaunchedEphemeral is not allowed")

      case _: TaskStateOp.ReservationTimeout =>
        TaskStateChange.Failure("ReservationTimeout on LaunchedEphemeral is not allowed")

      case _: TaskStateOp.Reserve =>
        TaskStateChange.Failure("Reserve on LaunchedEphemeral is not allowed")
    }
  }

  object LaunchedEphemeral {
    private val log = LoggerFactory.getLogger(getClass)
  }

  /**
    * A Reserved task carries the information of reserved resources and persistent volumes
    * and is currently not launched.
    *
    * @param taskId The task Id
    * @param reservation Information about the reserved resources and persistent volumes
    */
  case class Reserved(
      taskId: Task.Id,
      agentInfo: AgentInfo,
      reservation: Reservation) extends Task {

    override def reservationWithVolumes: Option[Reservation] = Some(reservation)

    override def launched: Option[Launched] = None

    override def update(update: TaskStateOp): TaskStateChange = update match {
      case TaskStateOp.LaunchOnReservation(_, appVersion, status, networking) =>
        val updatedTask = LaunchedOnReservation(taskId, agentInfo, appVersion, status, networking, reservation)
        TaskStateChange.Update(updatedTask, Some(this))

      case _: TaskStateOp.ReservationTimeout =>
        TaskStateChange.Expunge(this)

      // if a LaunchOnReservation failed while persisting the task, we want to recreate the reserved task
      case TaskStateOp.Create(task) =>
        TaskStateChange.Update(task, None) // // FIXME (3221): Some(this)?

      // failure case
      case _: TaskStateOp.ForceExpunge =>
        TaskStateChange.Failure("Expunge on Reserved is not allowed")

      // failure case
      case _: TaskStateOp.Reserve =>
        TaskStateChange.NoChange(taskId)

      // failure case
      case _: TaskStateOp.MesosUpdate =>
        TaskStateChange.Failure("MesosUpdate on Reserved is not allowed")
    }
  }

  case class LaunchedOnReservation(
      taskId: Task.Id,
      agentInfo: AgentInfo,
      appVersion: Timestamp,
      status: Status,
      networking: Networking,
      reservation: Reservation) extends Task {

    import LaunchedOnReservation.log

    override def reservationWithVolumes: Option[Reservation] = Some(reservation)

    override def launched: Option[Launched] = Some(Task.Launched(appVersion, status, networking))

    private[this] def hasStartedRunning: Boolean = status.startedAt.isDefined

    //scalastyle:off cyclomatic.complexity method.length
    override def update(update: TaskStateOp): TaskStateChange = update match {
      // case 1: now running
      case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.Running(mesosStatus), now) if !hasStartedRunning =>
        val updated = copy(
          status = status.copy(
            startedAt = Some(now),
            mesosStatus = mesosStatus))
        TaskStateChange.Update(updated, Some(this))

      // case 2: terminal
      // FIXME (3221): handle task_lost, kill etc differently and set appropriate timeouts (if any)
      case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.Terminal(_), now) =>
        val updatedTask = Task.Reserved(
          taskId = taskId,
          agentInfo = agentInfo,
          reservation = reservation.copy(state = Task.Reservation.State.Suspended(timeout = None))
        )
        TaskStateChange.Update(updatedTask, Some(this))

      // case 3: health or state updated
      case TaskStateOp.MesosUpdate(_, taskStatus, _) =>
        updatedHealthOrState(status.mesosStatus, taskStatus.mesosStatus).map { newStatus =>
          val updatedTask = copy(status = status.copy(mesosStatus = Some(newStatus)))
          TaskStateChange.Update(updatedTask, Some(this))
        } getOrElse {
          log.debug("Ignoring status update for {}. Status did not change.", taskId)
          TaskStateChange.NoChange(taskId)
        }

      // failure case: LaunchOnReservation
      case _: TaskStateOp.LaunchOnReservation =>
        TaskStateChange.Failure("Unable to handle Launch op on LaunchedOnReservation}")

      // failure case: Timeout
      case _: TaskStateOp.ReservationTimeout =>
        TaskStateChange.Failure("ReservationTimeout on LaunchedOnReservation is not allowed")

      // failure case
      case TaskStateOp.Create(task) =>
        TaskStateChange.Failure("Create on LaunchedOnReservation is not allowed")

      // failure case
      case _: TaskStateOp.ForceExpunge =>
        TaskStateChange.Failure("Expunge on LaunchedOnReservation is not allowed")

      // failure case
      case _: TaskStateOp.Reserve =>
        TaskStateChange.Failure("Reserve on LaunchedOnReservation is not allowed")
    }
  }

  object LaunchedOnReservation {
    private val log = LoggerFactory.getLogger(getClass)
  }

  /** returns the new status if the health status has been added or changed, or if the state changed */
  private[this] def updatedHealthOrState(
    maybeCurrent: Option[MesosProtos.TaskStatus],
    maybeUpdate: Option[MesosProtos.TaskStatus]): Option[MesosProtos.TaskStatus] = {

    maybeUpdate match {
      case Some(update) =>
        maybeCurrent match {
          case Some(current) =>
            val healthy = update.hasHealthy && (!current.hasHealthy || current.getHealthy != update.getHealthy)
            val changed = healthy || current.getState != update.getState
            if (changed) {
              Some(update)
            }
            else {
              None
            }
          case None => Some(update)
        }
      case None => None
    }
  }

  def tasksById(tasks: Iterable[Task]): Map[Task.Id, Task] = tasks.iterator.map(task => task.taskId -> task).toMap

  case class Id(idString: String) {
    lazy val mesosTaskId: MesosProtos.TaskID = MesosProtos.TaskID.newBuilder().setValue(idString).build()
    lazy val appId: PathId = Id.appId(idString)
    override def toString: String = s"task [$idString]"
  }

  object Id {
    private val appDelimiter = "."
    private val TaskIdRegex = """^(.+)[\._]([^_\.]+)$""".r
    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def appId(taskId: String): PathId = {
      taskId match {
        case TaskIdRegex(appId, uuid) => PathId.fromSafePath(appId)
        case _                        => throw new MatchError(s"taskId $taskId is no valid identifier")
      }
    }

    def apply(mesosTaskId: MesosProtos.TaskID): Id = new Id(mesosTaskId.getValue)

    def forApp(appId: PathId): Id = {
      val taskId = appId.safePath + appDelimiter + uuidGenerator.generate()
      Task.Id(taskId)
    }
  }

  /**
    * Represents a reservation for all resources that are needed for launching a task
    * and associated persistent local volumes.
    */
  case class Reservation(volumeIds: Iterable[LocalVolumeId], state: Reservation.State)

  object Reservation {
    sealed trait State {
      /** Defines when this state should time out and for which reason */
      def timeout: Option[Timeout]
    }
    object State {
      /** A newly reserved resident task */
      case class New(timeout: Option[Timeout]) extends State
      /** A launched resident task, never has a timeout */
      case object Launched extends State {
        override def timeout: Option[Timeout] = None
      }
      /** A resident task that has been running before but terminated and can be relaunched */
      case class Suspended(timeout: Option[Timeout]) extends State
      /** A resident task whose reservation and persistent volumes are being destroyed */
      case class Garbage(timeout: Option[Timeout]) extends State
      /** An unknown resident task created because of unknown reservations/persistent volumes */
      case class Unknown(timeout: Option[Timeout]) extends State
    }

    /**
      * A timeout that eventually leads to a state transition
      * @param initiated When this timeout was setup
      * @param deadline When this timeout should become effective
      * @param reason The reason why this timeout was set up
      */
    case class Timeout(initiated: Timestamp, deadline: Timestamp, reason: Timeout.Reason)

    object Timeout {
      sealed trait Reason
      object Reason {
        /** A timeout because the task could not be relaunched */
        case object RelaunchEscalationTimeout extends Reason
        /** A timeout because we got no ack for reserved resources or persistent volumes */
        case object ReservationTimeout extends Reason
      }
    }

  }

  case class LocalVolume(id: LocalVolumeId, persistentVolume: PersistentVolume)

  case class LocalVolumeId(appId: PathId, containerPath: String, uuid: String) {
    import LocalVolumeId._
    lazy val idString = appId.safePath + delimiter + containerPath + delimiter + uuid

    override def toString: String = s"LocalVolume [$idString]"
  }

  object LocalVolumeId {
    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())
    private val delimiter = "#"
    private val LocalVolumeEncoderRE = s"^([^.]+)[$delimiter]([^.]+)[$delimiter]([^.]+)$$".r

    def apply(appId: PathId, volume: PersistentVolume): LocalVolumeId =
      LocalVolumeId(appId, volume.containerPath, uuidGenerator.generate().toString)

    def unapply(id: String): Option[(LocalVolumeId)] = id match {
      case LocalVolumeEncoderRE(app, path, uuid) => Some(LocalVolumeId(PathId.fromSafePath(app), path, uuid))
      case _                                     => None
    }
  }

  /**
    * Represents a task which has been launched (i.e. sent to Mesos for launching).
    */
  case class Launched(
      appVersion: Timestamp,
      status: Status,
      networking: Networking) {

    def hasStartedRunning: Boolean = status.startedAt.isDefined

    def ports: Iterable[Int] = networking.ports

    def ipAddresses: Iterable[MesosProtos.NetworkInfo.IPAddress] = networking match {
      case list: NetworkInfoList => list.addresses
      case _                     => Iterable.empty
    }
  }

  /**
    * Info relating to the host on which the task has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Iterable[MesosProtos.Attribute])

  /**
    * Contains information about the status of a launched task including timestamps for important
    * state transitions.
    *
    * @param stagedAt Despite its name, stagedAt is set on task creation and before the TASK_STAGED notification from
    *                 Mesos. This is important because we periodically check for any tasks with an old stagedAt
    *                 timestamp and kill them (See KillOverdueTasksActor).
    */
  case class Status(
    stagedAt: Timestamp,
    startedAt: Option[Timestamp] = None,
    mesosStatus: Option[MesosProtos.TaskStatus] = None)

  /** Info on how to reach the task in the network. */
  sealed trait Networking {
    def ports: Iterable[Int]
  }

  /** The task is reachable via host ports which are bound to [[AgentInfo#host]]. */
  case class HostPorts(ports: Iterable[Int]) extends Networking
  object HostPorts {
    def apply(ports: Int*): HostPorts = HostPorts(ports)
  }

  /**
    * The task has been launched with one-IP-per-task settings. The ports can be discovered
    * by inspecting the [[mesosphere.marathon.state.DiscoveryInfo]] in the [[mesosphere.marathon.state.AppDefinition]].
    */
  case class NetworkInfoList(networkInfoList: Iterable[MesosProtos.NetworkInfo]) extends Networking {
    import scala.collection.JavaConverters._
    def addresses: Iterable[MesosProtos.NetworkInfo.IPAddress] = networkInfoList.flatMap(_.getIpAddressesList.asScala)
    override def ports: Iterable[Int] = Iterable.empty
  }
  object NetworkInfoList {
    def apply(networkInfoList: MesosProtos.NetworkInfo*): NetworkInfoList = NetworkInfoList(networkInfoList)
  }

  case object NoNetworking extends Networking {
    override def ports: Iterable[Int] = Iterable.empty
  }

  object Terminated {
    def isTerminated(state: TaskState): Boolean = state match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST => true
      case _ => false
    }

    def unapply(state: TaskState): Option[TaskState] = if (isTerminated(state)) Some(state) else None
  }
}
