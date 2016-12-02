package mesosphere.marathon
package core.instance

import java.util.Base64

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.{ TaskUpdateEffect, TaskUpdateOperation }
import mesosphere.marathon.state.{ MarathonState, PathId, Timestamp, UnreachableStrategy }
import mesosphere.marathon.stream._
import mesosphere.mesos.Placed
import org.apache._
import org.apache.mesos.Protos.Attribute
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.annotation.tailrec
import scala.concurrent.duration._

// TODO: remove MarathonState stuff once legacy persistence is gone
case class Instance(
    instanceId: Instance.Id,
    agentInfo: Instance.AgentInfo,
    state: InstanceState,
    tasksMap: Map[Task.Id, Task],
    runSpecVersion: Timestamp,
    unreachableStrategy: UnreachableStrategy = UnreachableStrategy()) extends MarathonState[Protos.Json, Instance] with Placed {

  val runSpecId: PathId = instanceId.runSpecId
  val isLaunched: Boolean = tasksMap.nonEmpty && tasksMap.valuesIterator.forall(task => task.launched.isDefined)

  def isReserved: Boolean = state.condition == Condition.Reserved
  def isCreated: Boolean = state.condition == Condition.Created
  def isError: Boolean = state.condition == Condition.Error
  def isFailed: Boolean = state.condition == Condition.Failed
  def isFinished: Boolean = state.condition == Condition.Finished
  def isKilled: Boolean = state.condition == Condition.Killed
  def isKilling: Boolean = state.condition == Condition.Killing
  def isRunning: Boolean = state.condition == Condition.Running
  def isStaging: Boolean = state.condition == Condition.Staging
  def isStarting: Boolean = state.condition == Condition.Starting
  def isUnreachable: Boolean = state.condition == Condition.Unreachable
  def isUnreachableInactive: Boolean = state.condition == Condition.UnreachableInactive
  def isGone: Boolean = state.condition == Condition.Gone
  def isUnknown: Boolean = state.condition == Condition.Unknown
  def isDropped: Boolean = state.condition == Condition.Dropped
  def isTerminated: Boolean = state.condition.isTerminal
  def isActive: Boolean = state.condition.isActive

  import Instance.eventsGenerator

  // TODO(PODS): verify functionality and reduce complexity
  @SuppressWarnings(Array("TraversableHead"))
  def update(op: InstanceUpdateOperation): InstanceUpdateEffect = {
    // TODO(PODS): implement logic:
    // - propagate the change to the task
    // - calculate the new instance status based on the state of the task

    // TODO(PODS): make sure state transitions are allowed. maybe implement a simple state machine?
    op match {
      case InstanceUpdateOperation.MesosUpdate(instance, status, mesosStatus, now) =>
        val taskId = Task.Id(mesosStatus.getTaskId)
        tasksMap.get(taskId).map { task =>
          val taskEffect = task.update(TaskUpdateOperation.MesosUpdate(status, mesosStatus, now))
          taskEffect match {
            case TaskUpdateEffect.Update(updatedTask) =>
              val updated: Instance = updatedInstance(updatedTask, now)
              val events = eventsGenerator.events(status, updated, Some(updatedTask), now, updated.state.condition != this.state.condition)
              if (updated.tasksMap.values.forall(_.isTerminal)) {
                Instance.log.info("all tasks of {} are terminal, requesting to expunge", updated.instanceId)
                InstanceUpdateEffect.Expunge(updated, events)
              } else {
                InstanceUpdateEffect.Update(updated, oldState = Some(this), events)
              }

            // We might still become UnreachableInactive.
            case TaskUpdateEffect.Noop if status == Condition.Unreachable && this.state.condition != Condition.UnreachableInactive =>
              val updated: Instance = updatedInstance(task, now)
              if (updated.state.condition == Condition.UnreachableInactive) {
                val events = eventsGenerator.events(updated.state.condition, updated, Some(task), now, updated.state.condition != this.state.condition)
                InstanceUpdateEffect.Update(updated, oldState = Some(this), events)
              } else {
                InstanceUpdateEffect.Noop(instance.instanceId)
              }

            case TaskUpdateEffect.Noop =>
              InstanceUpdateEffect.Noop(instance.instanceId)

            case TaskUpdateEffect.Failure(cause) =>
              InstanceUpdateEffect.Failure(cause)

            case _ =>
              InstanceUpdateEffect.Failure("ForceExpunge should never delegated to an instance")
          }
        }.getOrElse(InstanceUpdateEffect.Failure(s"$taskId not found in $instanceId"))

      case InstanceUpdateOperation.LaunchOnReservation(_, newRunSpecVersion, timestamp, status, hostPorts) =>
        if (this.isReserved) {
          require(tasksMap.size == 1, "Residency is not yet implemented for task groups")

          // TODO(PODS): make this work for taskGroups
          val task = tasksMap.values.head
          val taskEffect = task.update(TaskUpdateOperation.LaunchOnReservation(newRunSpecVersion, status))
          taskEffect match {
            case TaskUpdateEffect.Update(updatedTask) =>
              val updated = this.copy(
                state = state.copy(
                  condition = Condition.Staging,
                  since = timestamp
                ),
                tasksMap = tasksMap.updated(task.taskId, updatedTask),
                runSpecVersion = newRunSpecVersion
              )
              val events = eventsGenerator.events(updated.state.condition, updated, task = None, timestamp, instanceChanged = updated.state.condition != this.state.condition)
              InstanceUpdateEffect.Update(updated, oldState = Some(this), events)

            case _ =>
              InstanceUpdateEffect.Failure(s"Unexpected taskUpdateEffect $taskEffect")
          }
        } else {
          InstanceUpdateEffect.Failure("LaunchOnReservation can only be applied to a reserved instance")
        }

      case InstanceUpdateOperation.ReservationTimeout(_) =>
        if (this.isReserved) {
          // TODO(PODS): don#t use Timestamp.now()
          val events = eventsGenerator.events(state.condition, this, task = None, Timestamp.now(), instanceChanged = true)
          InstanceUpdateEffect.Expunge(this, events)
        } else {
          InstanceUpdateEffect.Failure("ReservationTimeout can only be applied to a reserved instance")
        }

      case InstanceUpdateOperation.LaunchEphemeral(instance) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")

      case InstanceUpdateOperation.Reserve(_) =>
        InstanceUpdateEffect.Failure("Reserve cannot be passed to an existing instance")

      case InstanceUpdateOperation.Revert(oldState) =>
        InstanceUpdateEffect.Failure("Revert cannot be passed to an existing instance")

      case InstanceUpdateOperation.ForceExpunge(_) =>
        InstanceUpdateEffect.Failure("ForceExpunge cannot be passed to an existing instance")
    }
  }

  override def mergeFromProto(message: Protos.Json): Instance = {
    Json.parse(message.getJson).as[Instance]
  }
  override def mergeFromProto(bytes: Array[Byte]): Instance = {
    mergeFromProto(Protos.Json.parseFrom(bytes))
  }
  override def toProto: Protos.Json = {
    Protos.Json.newBuilder().setJson(Json.stringify(Json.toJson(this))).build()
  }
  override def version: Timestamp = runSpecVersion

  override def hostname: String = agentInfo.host

  override def attributes: Seq[Attribute] = agentInfo.attributes

  private[instance] def updatedInstance(updatedTask: Task, now: Timestamp): Instance = {
    val updatedTasks = tasksMap.updated(updatedTask.taskId, updatedTask)
    copy(tasksMap = updatedTasks, state = Instance.InstanceState(Some(state), updatedTasks, now, unreachableStrategy.timeUntilInactive))
  }
}

@SuppressWarnings(Array("DuplicateImport"))
object Instance {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  @SuppressWarnings(Array("LooksLikeInterpolatedString"))
  def apply(): Instance = {
    // required for legacy store, remove when legacy storage is removed.
    new Instance(
      // need to provide an Id that passes the regex parser but would never overlap with a user-specified value
      Instance.Id("$none.marathon-0"),
      AgentInfo("", None, Nil),
      InstanceState(Condition.Unknown, Timestamp.zero, activeSince = None, healthy = None),
      Map.empty[Task.Id, Task],
      Timestamp.zero)
  }

  private val eventsGenerator = InstanceChangedEventsGenerator
  private val log: Logger = LoggerFactory.getLogger(classOf[Instance])

  def instancesById(tasks: Seq[Instance]): Map[Instance.Id, Instance] =
    tasks.map(task => task.instanceId -> task)(collection.breakOut)

  /**
    * Describes the state of an instance which is an accumulation of task states.
    *
    * @param condition The condition of the instance such as running, killing, killed.
    * @param since Denotes when the state was *first* update to the current condition.
    * @param activeSince Denotes the first task startedAt timestamp if any.
    * @param healthy Tells if all tasks run healthily if health checks have been enabled.
    */
  case class InstanceState(condition: Condition, since: Timestamp, activeSince: Option[Timestamp], healthy: Option[Boolean])

  object InstanceState {

    // Define task condition priorities.
    // If 2 tasks are Running and 2 tasks already Finished, the final status is Running.
    // If one task is Error and one task is Staging, the instance status is Error.
    val conditionHierarchy: (Condition) => Int = Seq(
      // If one task has one of the following conditions that one is assigned.
      Condition.Error,
      Condition.Failed,
      Condition.Gone,
      Condition.Dropped,
      Condition.Unreachable,
      Condition.Killing,
      Condition.Starting,
      Condition.Staging,
      Condition.Unknown,

      //From here on all tasks are either Created, Reserved, Running, Finished, or Killed
      Condition.Created,
      Condition.Reserved,
      Condition.Running,
      Condition.Finished,
      Condition.Killed
    ).indexOf(_)

    /**
      * Construct a new InstanceState.
      *
      * @param maybeOldState The old state of the instance if any.
      * @param newTaskMap    New tasks and their status that form the update instance.
      * @param now           Timestamp of update.
      * @return new InstanceState
      */
    @SuppressWarnings(Array("TraversableHead"))
    def apply(
      maybeOldState: Option[InstanceState],
      newTaskMap: Map[Task.Id, Task],
      now: Timestamp,
      timeUntilInactive: FiniteDuration = 5.minutes): InstanceState = {

      val tasks = newTaskMap.values

      // compute the new instance condition
      val condition = conditionFromTasks(tasks, now, timeUntilInactive)

      val active: Option[Timestamp] = activeSince(tasks)

      val healthy = computeHealth(tasks.toVector)
      maybeOldState match {
        case Some(state) if state.condition == condition && state.healthy == healthy => state
        case _ => InstanceState(condition, now, active, healthy)
      }
    }

    /**
      * @return condition for instance with tasks.
      */
    def conditionFromTasks(tasks: Iterable[Task], now: Timestamp, timeUntilInactive: FiniteDuration): Condition = {
      if (tasks.isEmpty) {
        Condition.Unknown
      } else {
        // The smallest Condition according to conditionOrdering is the condition for the whole instance.
        tasks.view.map(_.status.condition).minBy(conditionHierarchy) match {
          case Condition.Unreachable if shouldBecomeInactive(tasks, now, timeUntilInactive) => Condition.UnreachableInactive
          case condition => condition
        }
      }
    }

    /**
      * @return the time when the first task of instance reported as started if any.
      */
    def activeSince(tasks: Iterable[Task]): Option[Timestamp] = {
      tasks.flatMap(_.status.startedAt) match {
        case Nil => None
        case nonEmptySeq => Some(nonEmptySeq.min)
      }
    }

    /**
      * @return if one of tasks has been UnreachableInactive for more than timeUntilInactive.
      */
    def shouldBecomeInactive(tasks: Iterable[Task], now: Timestamp, timeUntilInactive: FiniteDuration): Boolean = {
      tasks.exists(_.isUnreachableExpired(now, timeUntilInactive))
    }
  }

  private[this] def isRunningUnhealthy(task: Task): Boolean = {
    task.isRunning && task.status.mesosStatus.fold(false)(m => m.hasHealthy && !m.getHealthy)
  }
  private[this] def isRunningHealthy(task: Task): Boolean = {
    task.isRunning && task.status.mesosStatus.fold(false)(m => m.hasHealthy && m.getHealthy)
  }
  private[this] def isPending(task: Task): Boolean = {
    task.status.condition != Condition.Running && task.status.condition != Condition.Finished
  }

  /**
    * Infer the health status of an instance by looking at its tasks
    * @param tasks all tasks of an instance
    * @param foundHealthy used internally to track whether at least one running and
    *                     healthy task was found.
    * @return
    *         Some(true), if at least one task is Running and healthy and all other
    *         tasks are either Running or Finished and no task is unhealthy
    *         Some(false), if at least one task is Running and unhealthy
    *         None, if at least one task is not Running or Finished
    */
  @tailrec
  private[instance] def computeHealth(tasks: Seq[Task], foundHealthy: Option[Boolean] = None): Option[Boolean] = {
    tasks match {
      case Nil =>
        // no unhealthy running tasks and all are running or finished
        // TODO(PODS): we do not have sufficient information about the configured healthChecks here
        // E.g. if container A has a healthCheck and B doesn't, b.mesosStatus.hasHealthy will always be `false`,
        // but we don't know whether this is because no healthStatus is available yet, or because no HC is configured.
        // This is therefore simplified to `if there is no healthStatus with getHealthy == false, healthy is true`
        foundHealthy
      case head +: tail if isRunningUnhealthy(head) =>
        // there is a running task that is unhealthy => the instance is considered unhealthy
        Some(false)
      case head +: tail if isPending(head) =>
        // there is a task that is NOT Running or Finished => None
        None
      case head +: tail if isRunningHealthy(head) =>
        computeHealth(tail, Some(true))
      case head +: tail if !isRunningHealthy(head) =>
        computeHealth(tail, foundHealthy)
    }
  }

  case class Id(idString: String) extends Ordered[Id] {
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    lazy val executorIdString: String = Id.executorIdString(idString)

    override def toString: String = s"instance [$idString]"

    override def compare(that: Instance.Id): Int =
      if (this.getClass == that.getClass)
        idString.compare(that.idString)
      else this.compareTo(that)
  }

  object Id {
    // Regular expression to extract runSpecId from instanceId
    // instanceId = $runSpecId.(instance-|marathon-)$uuid
    val InstanceIdRegex = """^(.+)\.(instance-|marathon-)([^\.]+)$""".r

    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def runSpecId(instanceId: String): PathId = {
      instanceId match {
        case InstanceIdRegex(runSpecId, prefix, uuid) => PathId.fromSafePath(runSpecId)
        case _ => throw new MatchError("unable to extract runSpecId from instanceId " + instanceId)
      }
    }

    private def executorIdString(instanceId: String): String = {
      instanceId match {
        case InstanceIdRegex(runSpecId, prefix, uuid) => prefix + runSpecId + "." + uuid
        case _ => throw new MatchError("unable to extract executorId from instanceId " + instanceId)
      }
    }

    def forRunSpec(id: PathId): Id = Instance.Id(id.safePath + ".instance-" + uuidGenerator.generate())
  }

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Seq[mesos.Protos.Attribute])

  object AgentInfo {
    def apply(offer: org.apache.mesos.Protos.Offer): AgentInfo = AgentInfo(
      host = offer.getHostname,
      agentId = Some(offer.getSlaveId.getValue),
      attributes = offer.getAttributesList.toIndexedSeq
    )
  }

  /**
    * Marathon has requested (or will request) that this instance be launched by Mesos.
    *
    * @param instance is the thing that Marathon wants to launch
    */
  case class LaunchRequest(instance: Instance)

  implicit object AttributeFormat extends Format[mesos.Protos.Attribute] {
    override def reads(json: JsValue): JsResult[Attribute] = {
      json.validate[String].map { base64 =>
        mesos.Protos.Attribute.parseFrom(Base64.getDecoder.decode(base64))
      }
    }

    override def writes(o: Attribute): JsValue = {
      JsString(Base64.getEncoder.encodeToString(o.toByteArray))
    }
  }

  implicit object FiniteDurationFormat extends Format[FiniteDuration] {
    override def reads(json: JsValue): JsResult[FiniteDuration] = {
      json.validate[Long].map(_.seconds)
    }

    override def writes(o: FiniteDuration): JsValue = {
      Json.toJson(o.toSeconds)
    }
  }

  implicit object KillSelectionFormat extends Format[UnreachableStrategy.KillSelection] {
    override def reads(json: JsValue): JsResult[UnreachableStrategy.KillSelection] = {
      json.validate[String].flatMap { selection: String =>
        try {
          JsSuccess(UnreachableStrategy.KillSelection.withName(selection))
        } catch {
          case e: NoSuchElementException => JsError(e.getMessage)
        }
      }
    }

    override def writes(o: UnreachableStrategy.KillSelection): JsValue = {
      Json.toJson(o.toString())
    }
  }

  implicit val unreachableStrategyFormat = Json.format[UnreachableStrategy]

  implicit val agentFormat: Format[AgentInfo] = Json.format[AgentInfo]
  implicit val idFormat: Format[Instance.Id] = Json.format[Instance.Id]
  implicit val instanceConditionFormat: Format[Condition] = Json.format[Condition]
  implicit val instanceStateFormat: Format[InstanceState] = Json.format[InstanceState]

  implicit val instanceJsonWrites: Writes[Instance] = Json.writes[Instance]
  implicit val unreachableStrategyReads: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Instance.Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState] ~
      (__ \ "unreachableStrategy").readNullable[UnreachableStrategy]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, maybeUnreachableStrategy) =>
        val unreachableStrategy = maybeUnreachableStrategy.getOrElse(UnreachableStrategy())
        new Instance(instanceId, agentInfo, state, tasksMap, runSpecVersion, unreachableStrategy)
      }
  }

  implicit lazy val tasksMapFormat: Format[Map[Task.Id, Task]] = Format(
    Reads.of[Map[String, Task]].map {
      _.map { case (k, v) => Task.Id(k) -> v }
    },
    Writes[Map[Task.Id, Task]] { m =>
      val stringToTask = m.map {
        case (k, v) => k.idString -> v
      }
      Json.toJson(stringToTask)
    }
  )
}

/**
  * Represents legacy handling for instances which was started in behalf of an AppDefinition. Take care that you
  * do not use this for other use cases than the following three:
  *
  * - HealthCheckActor (will be changed soon)
  * - InstanceOpFactoryHelper and InstanceOpFactoryImpl (start resident and ephemeral tasks for an AppDefinition)
  * - Migration to 1.4
  *
  * @param instanceId calculated instanceId based on the taskId
  * @param agentInfo according agent information of the task
  * @param state calculated instanceState based on taskState
  * @param tasksMap a map of one key/value pair consisting of the actual task
  * @param runSpecVersion the version of the task related runSpec
  */
class LegacyAppInstance(
  instanceId: Instance.Id,
  agentInfo: Instance.AgentInfo,
  state: InstanceState,
  tasksMap: Map[Task.Id, Task],
  runSpecVersion: Timestamp) extends Instance(instanceId, agentInfo, state, tasksMap, runSpecVersion)

object LegacyAppInstance {
  def apply(task: Task, unreachableStrategy: UnreachableStrategy = UnreachableStrategy()): Instance = {
    val since = task.status.startedAt.getOrElse(task.status.stagedAt)
    val tasksMap = Map(task.taskId -> task)
    val state = Instance.InstanceState(None, tasksMap, since)

    new Instance(task.taskId.instanceId, task.agentInfo, state, tasksMap, task.runSpecVersion, unreachableStrategy)
  }
}
