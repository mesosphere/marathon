package mesosphere.marathon.core.task

import java.util.Base64

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.task.Task.Reservation.Timeout.Reason.{ RelaunchEscalationTimeout, ReservationTimeout }
import mesosphere.marathon.state.{ PathId, PersistentVolume, RunSpec, Timestamp }
import org.apache.mesos
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory
// TODO PODS remove api imports
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.collection.immutable.Seq

//scalastyle:off number.of.types
/**
  * The state for launching a task. This might be a launched task or a reservation for launching a task or both.
  *
  * <pre>
  * +-----------------------+ +---------------------------------------+
  * +EPHEMERAL              + +               RESIDENT                |
  * +-----------------------+ +---------------------------------------+
  *                            _  _
  *   | match offer      ___ (~ )( ~)                  | match
  *   | & launch        /   \_\ \/ /                   | offer
  *   |                |   D_ ]\ \/                    | & reserve
  *   |            +-> |   D _]/\ \   <-+              |
  *   |   terminal |    \___/ / /\ \    | confirmed(?) |
  *   |     status |         (_ )( _)   | unreserve /  |
  *   v     update |       DELETED      | expunge      v
  *                |                    |
  * +--------------+--------+ +---------+---------------+
  * | LaunchedEphemeral     | | Reserved                |
  * |                       | |                         |
  * |  Started / Staged     | |  New / Launched         |
  * |  Running              | |  Suspended / Garbage    |
  * |                       | |  Unknown                | <-+
  * +-----------------------+ +-------------------------+   |
  *                           |                             |
  *                           |   +---------------------+   |
  *               match offer |   |LaunchedOnReservation|   | terminal
  *               & launch    |   |                     |   | status
  *                           |   | Started / Staged    |   | update
  *                           |   | Running             |   |
  *                           +-> |                     +---+
  *                               +---------------------+
  *
  * </pre>
  *
  * Note on wiping reserved tasks: It is not fully implemented right now so that
  * LaunchedOnReservation tasks might get immediately expunged right now.
  * Marathon will notice spurious tasks in the offer and create the appropriate
  * unreserve operations. See https://github.com/mesosphere/marathon/issues/3223
  */
sealed trait Task {
  def taskId: Task.Id
  def agentInfo: Instance.AgentInfo
  def reservationWithVolumes: Option[Task.Reservation]
  def launched: Option[Task.Launched]

  /** update the task based on the given trigger - depending on its state the task will decide what should happen */
  def update(update: InstanceStateOp): TaskStateChange

  def runSpecId: PathId = taskId.runSpecId

  def status: Task.Status

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

  def effectiveIpAddress(runSpec: RunSpec): Option[String] = {
    if (runSpec.ipAddress.isDefined)
      launched.flatMap(_.ipAddresses).flatMap(_.headOption).map(_.getIpAddress)
    else
      Some(agentInfo.host)
  }

  /**
    * convenience function added so that components can fold over this instead of matching
    * the type of task. Every Task should eventually have a version, then this can be removed.
    */
  def version: Option[Timestamp]
}

object Task {

  // TODO PODs remove api import
  import mesosphere.marathon.api.v2.json.Formats.{ TimestampFormat, PathIdFormat }

  /*
  object Implicits {
    import scala.language.implicitConversions

    implicit def tasksToAllPlaced(tasks: Seq[Task]): Seq[Placed] = tasks.map(taskAsPlaced(_))(collection.breakOut)

    implicit def taskAsPlaced(t: Task): Placed = new Placed {
      override def hostname: String = t.agentInfo.host
      override def attributes: Seq[Attribute] = t.agentInfo.attributes
    }
  }
  */

  case class Id(idString: String) extends Ordered[Id] {
    lazy val mesosTaskId: MesosProtos.TaskID = MesosProtos.TaskID.newBuilder().setValue(idString).build()
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    lazy val instanceId: Instance.Id = Instance.Id(this)
    override def toString: String = s"task [$idString]"
    override def compare(that: Id): Int = idString.compare(that.idString)
  }

  object Id {
    private val runSpecDelimiter = "."
    private val TaskIdRegex = """^(.+)[\._]([^_\.]+)$""".r
    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def runSpecId(taskId: String): PathId = {
      taskId match {
        case TaskIdRegex(runSpecId, uuid) => PathId.fromSafePath(runSpecId)
        case _ => throw new MatchError(s"taskId $taskId is no valid identifier")
      }
    }

    def apply(mesosTaskId: MesosProtos.TaskID): Id =
      new Id(mesosTaskId.getValue)

    def forRunSpec(id: PathId): Id = {
      val taskId = id.safePath + runSpecDelimiter + uuidGenerator.generate()
      Task.Id(taskId)
    }

    implicit val taskIdFormat = Format(
      Reads.of[String](Reads.minLength[String](3)).map(Task.Id(_)),
      Writes[Task.Id] { id => JsString(id.idString) }
    )
  }

  case class LocalVolume(id: LocalVolumeId, persistentVolume: PersistentVolume)

  case class LocalVolumeId(runSpecId: PathId, containerPath: String, uuid: String) {
    import LocalVolumeId._
    lazy val idString = runSpecId.safePath + delimiter + containerPath + delimiter + uuid

    override def toString: String = s"LocalVolume [$idString]"
  }

  object LocalVolumeId {
    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())
    private val delimiter = "#"
    private val LocalVolumeEncoderRE = s"^([^$delimiter]+)[$delimiter]([^$delimiter]+)[$delimiter]([^$delimiter]+)$$".r

    def apply(runSpecId: PathId, volume: PersistentVolume): LocalVolumeId =
      LocalVolumeId(runSpecId, volume.containerPath, uuidGenerator.generate().toString)

    def unapply(id: String): Option[(LocalVolumeId)] = id match {
      case LocalVolumeEncoderRE(runSpec, path, uuid) => Some(LocalVolumeId(PathId.fromSafePath(runSpec), path, uuid))
      case _ => None
    }

    implicit val localVolumeIdReader = (
      (__ \ "runSpecId").read[PathId] and
      (__ \ "containerPath").read[String] and
      (__ \ "uuid").read[String]
    )((id, path, uuid) => LocalVolumeId(id, path, uuid))

    implicit val localVolumeIdWriter = Writes[LocalVolumeId] { localVolumeId =>
      JsObject(Seq(
        "runSpecId" -> Json.toJson(localVolumeId.runSpecId),
        "containerPath" -> Json.toJson(localVolumeId.containerPath),
        "uuid" -> Json.toJson(localVolumeId.uuid),
        "persistenceId" -> Json.toJson(localVolumeId.idString)
      ))
    }
  }

  /**
    * Represents a task which has been launched (i.e. sent to Mesos for launching).
    *
    * @param hostPorts sequence of ports in the Mesos Agent allocated to the task
    */
  case class Launched(
      runSpecVersion: Timestamp,
      status: Status,
      hostPorts: Seq[Int]) {

    def hasStartedRunning: Boolean = status.startedAt.isDefined

    def ipAddresses: Option[Seq[MesosProtos.NetworkInfo.IPAddress]] =
      status.mesosStatus.flatMap(MesosStatus.ipAddresses)
  }

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
    mesosStatus: Option[MesosProtos.TaskStatus] = None,
    taskStatus: InstanceStatus)

  object Status {
    implicit object MesosTaskStatusFormat extends Format[mesos.Protos.TaskStatus] {
      override def reads(json: JsValue): JsResult[mesos.Protos.TaskStatus] = {
        json.validate[String].map { base64 =>
          mesos.Protos.TaskStatus.parseFrom(Base64.getDecoder.decode(base64))
        }
      }

      override def writes(o: TaskStatus): JsValue = {
        JsString(Base64.getEncoder.encodeToString(o.toByteArray))
      }
    }
    implicit val statusFormat = Json.format[Status]
  }

  object Terminated {
    def isTerminated(state: TaskState): Boolean = state match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST => true
      case _ => false
    }

    def unapply(state: TaskState): Option[TaskState] = if (isTerminated(state)) Some(state) else None
  }

  object MesosStatus {
    def ipAddresses(mesosStatus: MesosProtos.TaskStatus): Option[Seq[MesosProtos.NetworkInfo.IPAddress]] = {
      import scala.collection.JavaConverters._
      if (mesosStatus.hasContainerStatus && mesosStatus.getContainerStatus.getNetworkInfosCount > 0)
        Some(
          mesosStatus.getContainerStatus.getNetworkInfosList.asScala.flatMap(_.getIpAddressesList.asScala).toList
        )
      else None
    }
  }

  /**
    * A LaunchedEphemeral task is a stateless task that does not consume reserved resources or persistent volumes.
    */
  case class LaunchedEphemeral(
      taskId: Task.Id,
      agentInfo: Instance.AgentInfo,
      runSpecVersion: Timestamp,
      status: Status,
      hostPorts: Seq[Int]) extends Task {

    import LaunchedEphemeral.log

    override def reservationWithVolumes: Option[Reservation] = None

    override def launched: Option[Launched] = Some(Task.Launched(runSpecVersion, status, hostPorts))

    private[this] def hasStartedRunning: Boolean = status.startedAt.isDefined

    //scalastyle:off cyclomatic.complexity method.length
    override def update(update: InstanceStateOp): TaskStateChange = update match {
      // case 1: now running
      case InstanceStateOp.MesosUpdate(_, InstanceStatus.Running, mesosStatus, now) if !hasStartedRunning =>
        val updated = copy(
          status = status.copy(
            startedAt = Some(now),
            mesosStatus = Some(mesosStatus),
            taskStatus = InstanceStatus.Running))
        TaskStateChange.Update(newState = updated, oldState = Some(this))

      // case 2: terminal
      case InstanceStateOp.MesosUpdate(_, taskStatus: InstanceStatus.Terminal, mesosStatus, now) =>
        val updated = copy(status = status.copy(
          mesosStatus = Some(mesosStatus),
          taskStatus = taskStatus))
        TaskStateChange.Expunge(updated)

      // case 3: health or state updated
      case InstanceStateOp.MesosUpdate(_, taskStatus, mesosStatus, now) =>
        updatedHealthOrState(status.mesosStatus, mesosStatus) match {
          case Some(newStatus) =>
            val updatedTask = copy(status = status.copy(
              mesosStatus = Some(newStatus),
              taskStatus = taskStatus))
            TaskStateChange.Update(newState = updatedTask, oldState = Some(this))
          case None =>
            log.debug("Ignoring status update for {}. Status did not change.", taskId)
            TaskStateChange.NoChange(taskId)
        }

      case InstanceStateOp.ForceExpunge(_) =>
        TaskStateChange.Expunge(this)

      case InstanceStateOp.LaunchEphemeral(instance) =>
        TaskStateChange.Update(newState = instance.tasks.find(_.taskId == taskId).getOrElse(this), oldState = None)

      case _: InstanceStateOp.Revert =>
        TaskStateChange.Failure("Revert should not be handed over to a task instance")

      case _: InstanceStateOp.LaunchOnReservation =>
        TaskStateChange.Failure("LaunchOnReservation on LaunchedEphemeral is unexpected")

      case _: InstanceStateOp.ReservationTimeout =>
        TaskStateChange.Failure("ReservationTimeout on LaunchedEphemeral is unexpected")

      case _: InstanceStateOp.Reserve =>
        TaskStateChange.Failure("Reserve on LaunchedEphemeral is unexpected")
    }

    override def version: Option[Timestamp] = Some(runSpecVersion)
  }

  object LaunchedEphemeral {
    private val log = LoggerFactory.getLogger(getClass)
    implicit val launchedEphemeralFormat = Json.format[LaunchedEphemeral]
  }

  /**
    * Represents a reservation for all resources that are needed for launching a task
    * and associated persistent local volumes.
    */
  case class Reservation(volumeIds: Iterable[LocalVolumeId], state: Reservation.State)

  object Reservation {
    /**
      * A timeout that eventually leads to a state transition
      *
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

      implicit object ReasonFormat extends Format[Timeout.Reason] {
        override def reads(json: JsValue): JsResult[Timeout.Reason] = {
          json.validate[String].map {
            case "RelaunchEscalationTimeout" => RelaunchEscalationTimeout
            case "ReservationTimeout" => ReservationTimeout
          }
        }

        override def writes(o: Timeout.Reason): JsValue = {
          JsString(o.toString)
        }
      }
      implicit val timeoutFormat = Json.format[Timeout]
    }
    sealed trait State extends Product with Serializable {
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

      implicit object StateFormat extends Format[State] {
        override def reads(json: JsValue): JsResult[State] = {
          (json \ "timeout").validateOpt[Timeout].flatMap { timeout =>
            (json \ "name").validate[String].map {
              case "new" => New(timeout)
              case "launched" => Launched
              case "suspended" => Suspended(timeout)
              case "garbage" => Garbage(timeout)
              case _ => Unknown(timeout)
            }
          }
        }

        override def writes(o: State): JsValue = {
          val timeout = Json.toJson(o.timeout)
          o match {
            case _: New => JsObject(Seq("name" -> JsString("new"), "timeout" -> timeout))
            case Launched => JsObject(Seq("name" -> JsString("launched"), "timeout" -> timeout))
            case _: Suspended => JsObject(Seq("name" -> JsString("suspended"), "timeout" -> timeout))
            case _: Garbage => JsObject(Seq("name" -> JsString("garbage"), "timeout" -> timeout))
            case _: Unknown => JsObject(Seq("name" -> JsString("unknown"), "timeout" -> timeout))
          }
        }
      }
    }
    implicit val reservationFormat = Json.format[Reservation]
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
      agentInfo: Instance.AgentInfo,
      reservation: Reservation,
      status: Status) extends Task {

    override def reservationWithVolumes: Option[Reservation] = Some(reservation)

    override def launched: Option[Launched] = None

    override def update(update: InstanceStateOp): TaskStateChange = update match {
      case InstanceStateOp.LaunchOnReservation(_, runSpecVersion, taskStatus, hostPorts) =>
        val updatedTask = LaunchedOnReservation(taskId, agentInfo, runSpecVersion, taskStatus, hostPorts, reservation)
        TaskStateChange.Update(newState = updatedTask, oldState = Some(this))

      case _: InstanceStateOp.ReservationTimeout =>
        TaskStateChange.Expunge(this)

      case _: InstanceStateOp.ForceExpunge =>
        TaskStateChange.Expunge(this)

      case _: InstanceStateOp.Reserve =>
        TaskStateChange.NoChange(taskId)

      // failure case
      case _: InstanceStateOp.Revert =>
        TaskStateChange.Failure("Revert should not be handed over to a task instance")

      // failure case
      case InstanceStateOp.LaunchEphemeral(task) =>
        TaskStateChange.Failure("Launch should not be handed over to a task instance")

      // failure case
      case _: InstanceStateOp.MesosUpdate =>
        TaskStateChange.Failure("MesosUpdate on Reserved is unexpected")
    }

    override def version: Option[Timestamp] = None // TODO also Reserved tasks have a version
  }

  object Reserved {
    implicit val reservedFormat = Json.format[Reserved]
  }

  case class LaunchedOnReservation(
      taskId: Task.Id,
      agentInfo: Instance.AgentInfo,
      runSpecVersion: Timestamp,
      status: Status,
      hostPorts: Seq[Int],
      reservation: Reservation) extends Task {

    import LaunchedOnReservation.log

    override def reservationWithVolumes: Option[Reservation] = Some(reservation)

    override def launched: Option[Launched] = Some(Task.Launched(runSpecVersion, status, hostPorts))

    private[this] def hasStartedRunning: Boolean = status.startedAt.isDefined

    //scalastyle:off cyclomatic.complexity method.length
    override def update(update: InstanceStateOp): TaskStateChange = update match {
      // case 1: now running
      case InstanceStateOp.MesosUpdate(_, InstanceStatus.Running, mesosStatus, now) if !hasStartedRunning =>
        val updated = copy(
          status = status.copy(
            startedAt = Some(now),
            mesosStatus = Some(mesosStatus),
            taskStatus = InstanceStatus.Running))
        TaskStateChange.Update(newState = updated, oldState = Some(this))

      // case 2: terminal
      // FIXME (3221): handle task_lost, kill etc differently and set appropriate timeouts (if any)
      case InstanceStateOp.MesosUpdate(task, taskStatus: InstanceStatus.Terminal, mesosStatus, now) =>
        val updatedTask = Task.Reserved(
          taskId = taskId,
          agentInfo = agentInfo,
          reservation = reservation.copy(state = Task.Reservation.State.Suspended(timeout = None)),
          status = Task.Status(
            stagedAt = task.status.stagedAt,
            startedAt = task.status.startedAt,
            mesosStatus = Some(mesosStatus),
            taskStatus = taskStatus
          )
        )
        TaskStateChange.Update(newState = updatedTask, oldState = Some(this))

      // case 3: health or state updated
      case InstanceStateOp.MesosUpdate(_, taskStatus, mesosStatus, _) =>
        updatedHealthOrState(status.mesosStatus, mesosStatus).map { newStatus =>
          val updatedTask = copy(status = status.copy(
            mesosStatus = Some(newStatus),
            taskStatus = taskStatus))
          TaskStateChange.Update(newState = updatedTask, oldState = Some(this))
        } getOrElse {
          log.debug("Ignoring status update for {}. Status did not change.", taskId)
          TaskStateChange.NoChange(taskId)
        }

      case _: InstanceStateOp.ForceExpunge =>
        TaskStateChange.Expunge(this)

      // failure case: LaunchOnReservation
      case _: InstanceStateOp.LaunchOnReservation =>
        TaskStateChange.Failure("Unable to handle Launch op on LaunchedOnReservation}")

      // failure case: Timeout
      case _: InstanceStateOp.ReservationTimeout =>
        TaskStateChange.Failure("ReservationTimeout on LaunchedOnReservation is unexpected")

      // failure case
      case InstanceStateOp.LaunchEphemeral(task) =>
        TaskStateChange.Failure("Launch on LaunchedOnReservation is unexpected")

      // failure case
      case _: InstanceStateOp.Reserve =>
        TaskStateChange.Failure("Reserve on LaunchedOnReservation is unexpected")

      // failure case
      case _: InstanceStateOp.Revert =>
        TaskStateChange.Failure("Revert should not be handed over to a task instance")
    }

    override def version: Option[Timestamp] = Some(runSpecVersion)
  }

  object LaunchedOnReservation {
    private val log = LoggerFactory.getLogger(getClass)
    implicit val launchedOnReservationFormat = Json.format[LaunchedOnReservation]
  }

  /** returns the new status if the health status has been added or changed, or if the state changed */
  private[this] def updatedHealthOrState(
    maybeCurrent: Option[MesosProtos.TaskStatus],
    update: MesosProtos.TaskStatus): Option[MesosProtos.TaskStatus] = {

    maybeCurrent match {
      case Some(current) =>
        val healthy = update.hasHealthy && (!current.hasHealthy || current.getHealthy != update.getHealthy)
        val changed = healthy || current.getState != update.getState
        if (changed) {
          Some(update)
        } else {
          None
        }
      case None => Some(update)
    }
  }

  def reservedTasks(tasks: Iterable[Task]): Iterable[Task.Reserved] = tasks.collect { case r: Task.Reserved => r }

  def tasksById(tasks: Iterable[Task]): Map[Task.Id, Task] = tasks.iterator.map(task => task.taskId -> task).toMap

  implicit class TaskStatusComparison(val task: Task) extends AnyVal {
    def isReserved: Boolean = task.status.taskStatus == InstanceStatus.Reserved
    def isCreated: Boolean = task.status.taskStatus == InstanceStatus.Created
    def isError: Boolean = task.status.taskStatus == InstanceStatus.Error
    def isFailed: Boolean = task.status.taskStatus == InstanceStatus.Failed
    def isFinished: Boolean = task.status.taskStatus == InstanceStatus.Finished
    def isKilled: Boolean = task.status.taskStatus == InstanceStatus.Killed
    def isKilling: Boolean = task.status.taskStatus == InstanceStatus.Killing
    def isRunning: Boolean = task.status.taskStatus == InstanceStatus.Running
    def isStaging: Boolean = task.status.taskStatus == InstanceStatus.Staging
    def isStarting: Boolean = task.status.taskStatus == InstanceStatus.Starting
    def isUnreachable: Boolean = task.status.taskStatus == InstanceStatus.Unreachable
    def isGone: Boolean = task.status.taskStatus == InstanceStatus.Gone
    def isUnknown: Boolean = task.status.taskStatus == InstanceStatus.Unknown
    def isDropped: Boolean = task.status.taskStatus == InstanceStatus.Dropped
  }

  implicit object TaskFormat extends Format[Task] {
    override def reads(json: JsValue): JsResult[Task] = {
      json.validate[LaunchedEphemeral].orElse(json.validate[Reserved]).orElse(json.validate[LaunchedOnReservation])
    }

    override def writes(o: Task): JsValue = o match {
      case f: LaunchedEphemeral => Json.toJson(f)(LaunchedEphemeral.launchedEphemeralFormat)
      case r: Reserved => Json.toJson(r)(Reserved.reservedFormat)
      case lr: LaunchedOnReservation => Json.toJson(lr)(LaunchedOnReservation.launchedOnReservationFormat)
    }
  }
}
