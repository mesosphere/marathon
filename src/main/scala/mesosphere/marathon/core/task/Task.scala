package mesosphere.marathon
package core.task

import java.util.Base64

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Terminal
import mesosphere.marathon.core.instance.{ Instance, Reservation }
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.update.{ TaskUpdateEffect, TaskUpdateOperation }
import mesosphere.marathon.state._
import org.apache.mesos
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import mesosphere.marathon.api.v2.json.Formats._
import play.api.libs.json._

/**
  * The state for launching a task. This might be a launched task or a reservation for launching a task or both.
  * Here a task is Reserved or LaunchedOnReservation only if the corresponding instance has a reservation.
  * Similarly, a task is LaunchedEphemeral, only if the corresponding instance has no reservation.
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
  * launched-on-reservation tasks might get immediately expunged right now.
  * Marathon will notice spurious tasks in the offer and create the appropriate
  * unreserve operations. See https://github.com/mesosphere/marathon/issues/3223
  */

case class Task(taskId: Task.Id, runSpecVersion: Timestamp, status: Task.Status) {
  import Task.log

  def runSpecId: PathId = taskId.runSpecId

  private[this] def hasStartedRunning: Boolean = status.startedAt.isDefined

  /** apply the given operation to a task */
  def update(instance: Instance, op: TaskUpdateOperation): TaskUpdateEffect = op match {

    // case 0 (a.k.a. exceptional case): the task is already terminal. Don't transition in this case.
    // This might be because the task terminated (e.g. finished) before Marathon issues a kill Request
    // to Mesos. Mesos will likely send back a TASK_LOST status update, because the task is no longer
    // known in Mesos. We'll never want to transition from one terminal state to another as a terminal
    // state should already be distinct enough.
    // related to https://github.com/mesosphere/marathon/pull/4531
    case op: TaskUpdateOperation if this.isTerminal =>
      log.warn(s"received $op for terminal $taskId, ignoring")
      TaskUpdateEffect.Noop

    // case 1: running
    case TaskUpdateOperation.MesosUpdate(Condition.Running, mesosStatus, now) if !hasStartedRunning =>
      val updatedNetworkInfo = status.networkInfo.update(mesosStatus)
      val updatedTask = copy(status = status.copy(
        mesosStatus = Some(mesosStatus),
        condition = Condition.Running,
        startedAt = Some(now),
        networkInfo = updatedNetworkInfo))
      TaskUpdateEffect.Update(newState = updatedTask)

    // case 2: terminal; extractor applies specific logic e.g. when an Unreachable task becomes Gone
    case TaskUpdateOperation.MesosUpdate(newStatus: Terminal, mesosStatus, _) =>
      // Note the task needs to transition to Reserved if the corresponding instance has a reservation,
      // otherwise the instance will not transition to Reserved.
      val newCondition = if (instance.hasReservation) Condition.Reserved else newStatus
      val updatedStatus = status.copy(mesosStatus = Some(mesosStatus), condition = newCondition)
      val updated = copy(status = updatedStatus)
      TaskUpdateEffect.Update(updated)

    // case 3: there are small edge cases in which Marathon thinks a resident task is reserved
    // but it is actually running (restore ZK backup, for example).
    // If Mesos says that it's running, then transition accordingly
    case update: TaskUpdateOperation.MesosUpdate if status.condition == Condition.Reserved =>
      if (update.condition.isActive) {
        val updatedStatus = status.copy(startedAt = Some(update.now), mesosStatus = Some(update.taskStatus))
        val updatedTask = Task(taskId = taskId, status = updatedStatus, runSpecVersion = runSpecVersion)
        TaskUpdateEffect.Update(updatedTask)
      } else {
        TaskUpdateEffect.Noop
      }

    // case 4: health or state updated
    case TaskUpdateOperation.MesosUpdate(newStatus, mesosStatus, _) =>
      // TODO(PODS): strange to use Condition here
      updatedHealthOrState(status.mesosStatus, mesosStatus).map { newTaskStatus =>
        val updatedNetworkInfo = status.networkInfo.update(mesosStatus)
        val updatedStatus = status.copy(
          mesosStatus = Some(newTaskStatus), condition = newStatus, networkInfo = updatedNetworkInfo)
        val updatedTask = copy(status = updatedStatus)
        // TODO(PODS): The instance needs to handle a terminal task via an Update here
        // Or should we use Expunge in case of a terminal update for resident tasks?
        TaskUpdateEffect.Update(newState = updatedTask)
      } getOrElse {
        log.debug("Ignoring status update for {}. Status did not change.", taskId)
        TaskUpdateEffect.Noop
      }

    // case 5: launch a task once resources are reserved
    case TaskUpdateOperation.LaunchOnReservation(newTaskId, newRunSpecVersion, taskStatus) =>
      val updatedTask = Task(taskId = newTaskId, runSpecVersion = newRunSpecVersion, status = taskStatus)
      TaskUpdateEffect.Update(updatedTask)
  }

  /** returns the new status if the health status has been added or changed, or if the state changed */
  private[this] def updatedHealthOrState(
    maybeCurrent: Option[MesosProtos.TaskStatus],
    update: MesosProtos.TaskStatus): Option[MesosProtos.TaskStatus] = {
    maybeCurrent match {
      case Some(current) =>
        val healthy = update.hasHealthy && (!current.hasHealthy || current.getHealthy != update.getHealthy)
        val changed = healthy || current.getState != update.getState
        if (changed) Some(update) else None
      case None => Some(update)
    }
  }

  def launchedMesosId: Option[MesosProtos.TaskID] = {
    // it doesn't make sense to return a Mesos task ID for an inactive task
    if (status.condition.isActive) Some(taskId.mesosTaskId) else None
  }

  /**
    * @return whether task has an unreachable Mesos status longer than timeout.
    */
  def isUnreachableExpired(now: Timestamp, timeout: FiniteDuration): Boolean = {
    if (status.condition == Condition.Unreachable || status.condition == Condition.UnreachableInactive) {
      status.mesosStatus.exists { status =>
        val since: Timestamp =
          if (status.hasUnreachableTime) status.getUnreachableTime
          else Timestamp.fromTaskStatus(status)

        since.expired(now, by = timeout)
      }
    } else false
  }
}

object Task {
  private val log = LoggerFactory.getLogger(getClass)

  case class Id(idString: String) extends Ordered[Id] {
    lazy val mesosTaskId: MesosProtos.TaskID = MesosProtos.TaskID.newBuilder().setValue(idString).build()
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    lazy val instanceId: Instance.Id = Id.instanceId(idString)
    lazy val containerName: Option[String] = Id.containerName(idString)
    /**
      * For resident tasks, this will denote the number of launched tasks on a reservation since 1.5
      */
    lazy val attempt: Option[Long] = Id.attempt(idString)

    override def toString: String = s"task [$idString]"
    override def compare(that: Id): Int = idString.compare(that.idString)
  }

  object Id {

    @SuppressWarnings(Array("LooksLikeInterpolatedString"))
    object Names {
      val anonymousContainer = "$anon" // presence of `$` is important since it's illegal for a real container name!
    }
    // Regular expression for matching taskIds before instance-era
    private val LegacyTaskIdRegex = """^(.+)[\._]([^_\.]+)$""".r

    // Regular expression for matching resident app taskIds
    private val ResidentTaskIdRegex = """^(.+)([\._])([^_\.]+)(\.)(\d+)$""".r
    private val ResidentTaskIdAttemptSeparator = "."

    // Regular expression for matching taskIds since instance-era
    private val TaskIdWithInstanceIdRegex = """^(.+)\.(instance-|marathon-)([^_\.]+)[\._]([^_\.]+)$""".r
    private val ResidentTaskIdWithInstanceIdRegex = """^(.+)\.(instance-|marathon-)([^_\.]+)[\._]([^_\.]+)\.(\d+)$""".r

    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def runSpecId(taskId: String): PathId = {
      taskId match {
        case ResidentTaskIdWithInstanceIdRegex(runSpecId, prefix, uuid, container, attempt) =>
          PathId.fromSafePath(runSpecId)
        case TaskIdWithInstanceIdRegex(runSpecId, _, _, _) => PathId.fromSafePath(runSpecId)
        case ResidentTaskIdRegex(runSpecId, _, uuid, _, attempt) => PathId.fromSafePath(runSpecId)
        case LegacyTaskIdRegex(runSpecId, uuid) => PathId.fromSafePath(runSpecId)
        case _ => throw new MatchError(s"taskId $taskId is no valid identifier")
      }
    }

    def containerName(taskId: String): Option[String] = {
      taskId match {
        case ResidentTaskIdWithInstanceIdRegex(runSpecId, prefix, uuid, container, attempt) =>
          if (container == Names.anonymousContainer) None else Some(container)
        case TaskIdWithInstanceIdRegex(runSpecId, prefix, instanceUuid, maybeContainer) =>
          if (maybeContainer == Names.anonymousContainer) None else Some(maybeContainer)
        case ResidentTaskIdRegex(runSpecId, _, uuid, _, attempt) => None
        case LegacyTaskIdRegex(runSpecId, uuid) => None
        case _ => throw new MatchError(s"taskId $taskId is no valid identifier")
      }
    }

    def attempt(taskId: String): Option[Long] = {
      taskId match {
        case ResidentTaskIdWithInstanceIdRegex(runSpecId, prefix, uuid, container, attempt) => Some(attempt.toLong)
        case ResidentTaskIdRegex(runSpecId, _, uuid, _, attempt) => Some(attempt.toLong)
        case _ => None
      }
    }

    def reservationId(taskId: String): String = {
      taskId match {
        case ResidentTaskIdWithInstanceIdRegex(runSpecId, prefix, uuid, container, attempt) =>
          Reservation.Id(runSpecId, ".", Some(prefix), uuid).toString
        case TaskIdWithInstanceIdRegex(runSpecId, prefix, instanceUuid, uuid) =>
          Reservation.Id(runSpecId, ".", Some(prefix), instanceUuid).toString
        case ResidentTaskIdRegex(runSpecId, separator, uuid, _, attempt) =>
          Reservation.Id(runSpecId, separator, None, uuid).toString
        case LegacyTaskIdRegex(runSpecId, uuid) =>
          Reservation.Id(runSpecId, ".", None, uuid).toString
        case _ => taskId
      }
    }

    def instanceId(taskId: String): Instance.Id = {
      taskId match {
        case ResidentTaskIdWithInstanceIdRegex(runSpecId, prefix, uuid, container, attempt) =>
          Instance.Id(runSpecId + "." + prefix + uuid)
        case TaskIdWithInstanceIdRegex(runSpecId, prefix, instanceUuid, uuid) =>
          Instance.Id(runSpecId + "." + prefix + instanceUuid)
        case ResidentTaskIdRegex(runSpecId, _, uuid, _, attempt) =>
          Instance.Id(runSpecId + "." + calculateLegacyExecutorId(uuid))
        case LegacyTaskIdRegex(runSpecId, uuid) =>
          Instance.Id(runSpecId + "." + calculateLegacyExecutorId(uuid))
        case _ => throw new MatchError(s"taskId $taskId is no valid identifier")
      }
    }

    def apply(mesosTaskId: MesosProtos.TaskID): Id = new Id(mesosTaskId.getValue)

    /**
      * Create a taskId according to the old schema (no instance designator, no mesos container name).
      * Use this when needing to create an ID for a normal App task or a task for initial reservation handling.
      *
      * Use @forResidentTask when you want to launch a task on an existing reservation.
      */
    def forRunSpec(id: PathId): Id = {
      val taskId = id.safePath + "." + uuidGenerator.generate()
      Task.Id(taskId)
    }

    /**
      * Create a taskId for a pod instance's task. This will create a taskId designating the instance and each
      * task container's name.
      * @param instanceId the ID of the instance that this task is contained in
      * @param container the name of the task as per the pod container config.
      */
    def forInstanceId(instanceId: Instance.Id, container: Option[MesosContainer]): Id =
      Id(instanceId.idString + "." + container.map(c => c.name).getOrElse(Names.anonymousContainer))

    /**
      * Create a taskId for a resident task launch. This will append or increment a launch attempt count that might
      * contained within the given taskId, and will be part of the resulting taskId.
      *
      * Example: app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6 results in app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.1
      * Example: app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.41 results in app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.42
      * @param taskId The ID of the previous task that was used to match offers.
      */
    def forResidentTask(taskId: Task.Id): Task.Id = {
      taskId.idString match {
        case ResidentTaskIdWithInstanceIdRegex(runSpecId, prefix, uuid, container, attempt) =>
          val newAttempt = attempt.toLong + 1
          val newIdString = s"$runSpecId.$prefix$uuid.$container.$newAttempt"
          Task.Id(newIdString)
        case ResidentTaskIdRegex(runSpecId, separator1, uuid, separator2, attempt) =>
          val newAttempt = attempt.toLong + 1
          val newIdString = s"$runSpecId$separator1$uuid$separator2$newAttempt"
          Task.Id(newIdString)
        // this is the fallback case and must come second to prevent changing the existing regex
        case LegacyTaskIdRegex(runSpecId, uuid) =>
          // the initial dummy task created when reserving does not have an attempt count
          // when actually launching a task based on a reservation, we consider this attempt #1
          val newIdString = taskId.idString + ResidentTaskIdAttemptSeparator + "1"
          Task.Id(newIdString)
        case _ =>
          // TODO we cannot really handle this but throwing an exception here is no good.
          throw new RuntimeException(s"$taskId is no valid resident taskId")
      }
    }

    implicit val taskIdFormat = Format(
      Reads.of[String](Reads.minLength[String](3)).map(Task.Id(_)),
      Writes[Task.Id] { id => JsString(id.idString) }
    )

    // pre-instance-era executorId="marathon-$taskId" and compatibility reasons we need this calculation.
    // Should be removed as soon as no tasks without instance exists (tbd)
    def calculateLegacyExecutorId(taskId: String): String = s"marathon-$taskId"
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
      condition: Condition,
      networkInfo: NetworkInfo) {

    /**
      * @return the health status reported by mesos for this task
      */
    def healthy: Option[Boolean] = mesosStatus.withFilter(_.hasHealthy).map(_.getHealthy)
  }

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

  implicit class TaskStatusComparison(val task: Task) extends AnyVal {
    def isReserved: Boolean = task.status.condition == Condition.Reserved
    def isCreated: Boolean = task.status.condition == Condition.Created
    def isError: Boolean = task.status.condition == Condition.Error
    def isFailed: Boolean = task.status.condition == Condition.Failed
    def isFinished: Boolean = task.status.condition == Condition.Finished
    def isKilled: Boolean = task.status.condition == Condition.Killed
    def isKilling: Boolean = task.status.condition == Condition.Killing
    def isRunning: Boolean = task.status.condition == Condition.Running
    def isStaging: Boolean = task.status.condition == Condition.Staging
    def isStarting: Boolean = task.status.condition == Condition.Starting
    def isUnreachable: Boolean = task.status.condition == Condition.Unreachable
    def isUnreachableInactive: Boolean = task.status.condition == Condition.UnreachableInactive
    def isGone: Boolean = task.status.condition == Condition.Gone
    def isUnknown: Boolean = task.status.condition == Condition.Unknown
    def isDropped: Boolean = task.status.condition == Condition.Dropped
    def isTerminal: Boolean = task.status.condition.isTerminal
    def isActive: Boolean = task.status.condition.isActive
  }

  implicit val taskFormat: Format[Task] = Json.format[Task]
}
