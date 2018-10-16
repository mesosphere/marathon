package mesosphere.marathon
package core.task

import java.util.{Base64, UUID}

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Terminal
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state._
import org.apache.mesos
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.Protos.{TaskState, TaskStatus}
import org.apache.mesos.{Protos => MesosProtos}

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

case class Task(taskId: Task.Id, runSpecVersion: Timestamp, status: Task.Status) extends StrictLogging {

  def runSpecId: PathId = taskId.runSpecId

  private[this] def hasStartedRunning: Boolean = status.startedAt.isDefined

  /** apply the given operation to a task */
  def update(instance: Instance, newStatus: Condition, newMesosStatus: MesosProtos.TaskStatus, now: Timestamp): TaskUpdateEffect = {

    // Exceptional case: the task is already terminal. Don't transition in this case.
    // This might be because the task terminated (e.g. finished) before Marathon issues a kill Request
    // to Mesos. Mesos will likely send back a TASK_LOST status update, because the task is no longer
    // known in Mesos. We'll never want to transition from one terminal state to another as a terminal
    // state should already be distinct enough.
    // related to https://github.com/mesosphere/marathon/pull/4531
    if (this.isTerminal) {
      logger.warn(s"received update($newStatus, $newMesosStatus, $now) for terminal $taskId, ignoring")
      return TaskUpdateEffect.Noop
    }

    newStatus match {

      // case 1: running
      case Condition.Running if !hasStartedRunning =>
        val updatedNetworkInfo = status.networkInfo.update(newMesosStatus)
        val updatedTask = copy(status = status.copy(
          mesosStatus = Some(newMesosStatus),
          condition = Condition.Running,
          startedAt = Some(now),
          networkInfo = updatedNetworkInfo))
        TaskUpdateEffect.Update(newState = updatedTask)

      // case 2: terminal; extractor applies specific logic e.g. when an Unreachable task becomes Gone
      case _: Terminal =>
        val newCondition = if (instance.hasReservation) Condition.Reserved else newStatus
        val updatedStatus = status.copy(mesosStatus = Some(newMesosStatus), condition = newCondition)
        val updated = copy(status = updatedStatus)
        TaskUpdateEffect.Update(updated)

      // case 3: there are small edge cases in which Marathon thinks a resident task is reserved
      // but it is actually running (restore ZK backup, for example).
      // If Mesos says that it's running, then transition accordingly
      case _ if status.condition == Condition.Scheduled && instance.hasReservation =>
        if (newStatus.isActive) {
          val updatedStatus = status.copy(startedAt = Some(now), mesosStatus = Some(newMesosStatus))
          val updatedTask = Task(taskId = taskId, status = updatedStatus, runSpecVersion = runSpecVersion)
          TaskUpdateEffect.Update(updatedTask)
        } else {
          TaskUpdateEffect.Noop
        }

      // case 4: health or state updated
      case _ =>
        // TODO(PODS): strange to use Condition here
        updatedHealthOrState(status.mesosStatus, newMesosStatus).map { newTaskStatus =>
          val updatedNetworkInfo = status.networkInfo.update(newMesosStatus)
          val updatedStatus = status.copy(
            mesosStatus = Some(newTaskStatus), condition = newStatus, networkInfo = updatedNetworkInfo)
          val updatedTask = copy(status = updatedStatus)
          // TODO(PODS): The instance needs to handle a terminal task via an Update here
          // Or should we use Expunge in case of a terminal update for resident tasks?
          TaskUpdateEffect.Update(newState = updatedTask)
        } getOrElse {
          logger.debug(s"Ignoring status update for $taskId. Status did not change.")
          TaskUpdateEffect.Noop
        }

    }
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

  /**
    * Base for all task identifiers.
    */
  trait Id extends Ordered[Id] {
    // A stringifed version of the id.
    val idString: String

    // Quick access to the underlying run spec identifier of the task.
    val runSpecId: PathId

    // Quick access to the underlying instance identifier of the task.
    val instanceId: Instance.Id

    val reservationId: String

    // The Mesos task id representation of the task.
    lazy val mesosTaskId: MesosProtos.TaskID = MesosProtos.TaskID.newBuilder().setValue(idString).build()

    // pre-instance-era executorId="marathon-$taskId" and compatibility reasons we need this calculation.
    // Should be removed as soon as no tasks without instance exists (tbd)
    def calculateLegacyExecutorId(): String = s"marathon-$idString"

    override def compare(that: Id): Int = idString.compare(that.idString)

    /**
      * @return String representation for debugging.
      */
    override def toString: String = s"task [$idString]"

    val containerName: Option[String]
  }

  /**
    * A task identifier for legacy tasks. This cannot represent a pod instance. These ids won't be created but old Mesos
    * tasks might have such an instance thus we have to represent them.
    *
    * This id can also represent an old reservation for a persistent app, ie an app with resident tasks.
    *
    * The ids match [[Task.Id.LegacyTaskIdRegex]].
    *
    * Examples:
    *  - "myGroup_myApp.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6"
    *  - "myGroup_myApp.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6"
    *
    * @param runSpecId Identifies the run spec the task was started with.
    * @param separator This can be "." or "_".
    * @param uuid A unique identifier of the task.
    */
  case class LegacyId(val runSpecId: PathId, separator: String, uuid: UUID) extends Id {

    // A stringifed version of the id.
    override val idString: String = runSpecId.safePath + separator + uuid

    override lazy val instanceId: Instance.Id = Instance.Id(runSpecId, Instance.PrefixMarathon, uuid)

    override val reservationId = idString

    override val containerName: Option[String] = None
  }

  /**
    * Identifier for a launched persistent app task, ie a resident task. These ids do not represent resident tasks for
    * pods, ie tasks in a Mesos group.
    *
    * The ids match [[Task.Id.ResidentTaskIdRegex ]].
    *
    * Examples:
    *  - "myGroup_myApp.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.42"
    *  - "myGroup_myApp.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.2"
    *
    * @param runSpecId Identifies the run spec the task was started with.
    * @param separator This can be "." or "_".
    * @param uuid A unique identifier of the task.
    * @param attempt Counts how often a task has been launched on a specific reservation.
    */
  case class LegacyResidentId(val runSpecId: PathId, separator: String, uuid: UUID, attempt: Long) extends Id {

    // A stringifed version of the id.
    override val idString: String = runSpecId.safePath + separator + uuid + "." + attempt

    override lazy val instanceId: Instance.Id = Instance.Id(runSpecId, Instance.PrefixMarathon, uuid)

    override val reservationId = runSpecId.safePath + separator + uuid

    override val containerName: Option[String] = None
  }

  /**
    * Identifier of an ephemeral app or pod task.
    *
    * The ids match [[Task.Id.TaskIdWithInstanceIdRegex]]. They do not include any attempts.
    *
    * Tasks belonging to a pod, ie a Mesos task group, share the same instance id but have each a different container
    * name. This is the container name specified in the containers section of the run spec.
    *
    * Examples:
    *  - "myGroup_myApp.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.$anon"
    *  - "myGroup_myApp.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.$anon"
    *  - "myGroup_myApp.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.rails"
    *  - "myGroup_myApp.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.rails"
    *
    * @param instanceId Identifies the instance the task belongs to.
    * @param containerName If set identifies the container in the pod. Defaults to [[Task.Id.Names.anonymousContainer]].
    */
  case class EphemeralOrReservedTaskId(val instanceId: Instance.Id, val containerName: Option[String]) extends Id {

    // A stringifed version of the id.
    override val idString = instanceId.idString + "." + containerName.getOrElse(Id.Names.anonymousContainer)

    // Quick access to the underlying run spec identifier of the task.
    override lazy val runSpecId: PathId = instanceId.runSpecId

    override lazy val reservationId: String = instanceId.idString
  }

  /**
    * Identifier for a resident app or pod task, ie a task that launched on a reservation.
    *
    * The ids match [[Task.Id.ResidentTaskIdWithInstanceIdRegex]] and include a launch attempt.
    *
    * Examples:
    *  - "myGroup_myApp.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.$anon.1"
    *  - "myGroup_myApp.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.$anon.3"
    *  - "myGroup_myApp.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.rails.2"
    *  - "myGroup_myApp.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.rails.42"
    *
    * @param instanceId Identifies the instance the task belongs to.
    * @param containerName If set identifies the container in the pod. Defaults to [[Task.Id.Names.anonymousContainer]].
    * @param attempt Counts how often a task has been launched on a specific reservation.
    */
  case class ResidentTaskId(val instanceId: Instance.Id, val containerName: Option[String], attempt: Long) extends Id {

    // A stringifed version of the id.
    override val idString = instanceId.idString + "." + containerName.getOrElse(Id.Names.anonymousContainer) + "." + attempt

    // Quick access to the underlying run spec identifier of the task.
    override lazy val runSpecId: PathId = instanceId.runSpecId

    override lazy val reservationId: String = instanceId.idString
  }

  object Id {

    object Names {
      val anonymousContainer = "$anon" // presence of `$` is important since it's illegal for a real container name!
    }
    // Regular expression for matching taskIds before instance-era
    private val LegacyTaskIdRegex = """^(.+)([\._])([^_\.]+)$""".r
    private val ResidentTaskIdRegex = """^(.+)([\._])([^_\.]+)(\.)(\d+)$""".r

    // Regular expression for matching taskIds since instance-era
    private val TaskIdWithInstanceIdRegex = """^(.+)\.(instance-|marathon-)([^_\.]+)[\._]([^_\.]+)$""".r
    private val ResidentTaskIdWithInstanceIdRegex = """^(.+)\.(instance-|marathon-)([^_\.]+)[\._]([^_\.]+)\.(\d+)$""".r

    /**
      * Parse instance and task id from idString.
      *
      * The string has to match one of
      *   * LegacyTaskIdRegex
      *   * ResidentTaskIdRegex
      *   * TaskIdWithInstanceIdRegex
      *   * ResidentTaskIdWithInstanceIdRegex
      *
      * @param idString The raw id that should be parsed.
      * @return Task.Id
      */
    def apply(idString: String): Task.Id = {
      idString match {
        case ResidentTaskIdWithInstanceIdRegex(safeRunSpecId, prefix, uuid, container, attempt) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          val instanceId = Instance.Id(runSpec, Instance.Prefix.fromString(prefix), UUID.fromString(uuid))
          val containerName: Option[String] = if (container == Names.anonymousContainer) None else Some(container)
          ResidentTaskId(instanceId, containerName, attempt.toLong)
        case TaskIdWithInstanceIdRegex(safeRunSpecId, prefix, uuid, container) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          val instanceId = Instance.Id(runSpec, Instance.Prefix.fromString(prefix), UUID.fromString(uuid))
          val containerName: Option[String] = if (container == Names.anonymousContainer) None else Some(container)

          EphemeralOrReservedTaskId(instanceId, containerName)
        case ResidentTaskIdRegex(safeRunSpecId, separator, uuid, _, attempt) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          LegacyResidentId(runSpec, separator, UUID.fromString(uuid), attempt.toLong)
        case LegacyTaskIdRegex(safeRunSpecId, separator, uuid) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          LegacyId(runSpec, separator, UUID.fromString(uuid))
        case _ => throw new MatchError(s"taskId $idString no valid identifier")
      }
    }

    /**
      * Construct a task id from a Mesos task id.
      *
      * @param mesosTaskId The task identifier in the Mesos world.
      * @return Task id in Marathon world.
      */
    def apply(mesosTaskId: MesosProtos.TaskID): Id = apply(mesosTaskId.getValue)

    /**
      * Create a taskId for a pod instance's task. This will create a taskId designating the instance and each
      * task container's name. It may be used for reservations for persitent pods as well.
      *
      * @param instanceId the ID of the instance that this task is contained in
      * @param container the name of the task as per the pod container config.
      */
    def forInstanceId(instanceId: Instance.Id, container: Option[MesosContainer] = None): Id = EphemeralOrReservedTaskId(instanceId, container.map(_.name))

    /**
      * Create a taskId for a resident task launch. This will append or increment a launch attempt count that might
      * contained within the given taskId, and will be part of the resulting taskId.
      *
      * Example: app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6 results in app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.1
      * Example: app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.41 results in app.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.42
      * @param taskId The ID of the previous task that was used to match offers.
      */
    def forResidentTask(taskId: Task.Id): Task.Id = {
      taskId match {
        case EphemeralOrReservedTaskId(instanceId, containerName) =>
          ResidentTaskId(instanceId, containerName, 1L)
        case ResidentTaskId(instanceId, containerName, attempt) =>
          val newAttempt = attempt + 1L
          ResidentTaskId(instanceId, containerName, newAttempt)
        case LegacyResidentId(runSpecId, separator, uuid, attempt) =>
          val newAttempt = attempt + 1L
          LegacyResidentId(runSpecId, separator, uuid, newAttempt)
        case LegacyId(runSpecId, separator, uuid) =>
          LegacyResidentId(runSpecId, separator, uuid, 1L)
      }
    }

    implicit val taskIdFormat = Format(
      Reads.of[String](Reads.minLength[String](3)).map(Task.Id.apply(_)),
      Writes[Task.Id] { id => JsString(id.idString) }
    )
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
