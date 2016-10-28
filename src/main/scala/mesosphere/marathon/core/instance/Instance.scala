package mesosphere.marathon
package core.instance

import java.util.Base64

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.{ TaskUpdateEffect, TaskUpdateOperation }
import mesosphere.marathon.state.{ MarathonState, PathId, Timestamp }
import mesosphere.marathon.stream._
import mesosphere.mesos.Placed
import org.apache._
import org.apache.mesos.Protos.Attribute
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.{ Reads, Writes }

import scala.annotation.tailrec
// TODO: Remove timestamp format
import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
import play.api.libs.json.{ Format, JsResult, JsString, JsValue, Json }

// TODO: remove MarathonState stuff once legacy persistence is gone
case class Instance(
    instanceId: Instance.Id,
    agentInfo: Instance.AgentInfo,
    state: InstanceState,
    tasksMap: Map[Task.Id, Task],
    runSpecVersion: Timestamp) extends MarathonState[Protos.Json, Instance] with Placed {

  // TODO(PODS): check consumers of this def and see if they can use the map instead
  val tasks = tasksMap.values.to[Seq]
  val runSpecId: PathId = instanceId.runSpecId
  val isLaunched: Boolean = tasksMap.values.forall(task => task.launched.isDefined)

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
          val taskEffect = task.update(TaskUpdateOperation.LaunchOnReservation(newRunSpecVersion, status, hostPorts))
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
    copy(tasksMap = updatedTasks, state = Instance.newInstanceState(Some(state), updatedTasks, now))
  }
}

@SuppressWarnings(Array("DuplicateImport"))
object Instance {
  @SuppressWarnings(Array("LooksLikeInterpolatedString"))
  def apply(): Instance = {
    // required for legacy store, remove when legacy storage is removed.
    new Instance(
      // need to provide an Id that passes the regex parser but would never overlap with a user-specified value
      Instance.Id("$none.marathon-0"),
      AgentInfo("", None, Nil),
      InstanceState(Condition.Unknown, Timestamp.zero, healthy = None),
      Map.empty[Task.Id, Task],
      Timestamp.zero)
  }

  private val eventsGenerator = InstanceChangedEventsGenerator
  private val log: Logger = LoggerFactory.getLogger(classOf[Instance])

  /**
    * An instance can only have this status, if all tasks of the instance have this status.
    * The order of the status is important.
    * If 2 tasks are Running and 2 tasks already Finished, the final status is Running.
    */
  private val AllInstanceConditions: Seq[Condition] = Seq(
    Condition.Created,
    Condition.Reserved,
    Condition.Running,
    Condition.Finished,
    Condition.Killed
  )

  /**
    * An instance has this status, if at least one tasks of the instance has this status.
    * The order of the status is important.
    * If one task is Error and one task is Staging, the instance status is Error.
    */
  private val DistinctInstanceConditions: Seq[Condition] = Seq(
    Condition.Error,
    Condition.Failed,
    Condition.Gone,
    Condition.Dropped,
    Condition.Unreachable,
    Condition.Killing,
    Condition.Starting,
    Condition.Staging,
    Condition.Unknown
  )

  def instancesById(tasks: Seq[Instance]): Map[Instance.Id, Instance] =
    tasks.map(task => task.instanceId -> task)(collection.breakOut)

  case class InstanceState(condition: Condition, since: Timestamp, healthy: Option[Boolean])

  @SuppressWarnings(Array("TraversableHead"))
  private[instance] def newInstanceState(
    maybeOldState: Option[InstanceState],
    newTaskMap: Map[Task.Id, Task],
    timestamp: Timestamp): InstanceState = {

    val tasks = newTaskMap.values

    // compute the new instance state
    val conditionMap = tasks.groupBy(_.status.condition)
    val condition = if (conditionMap.size == 1) {
      // all tasks have the same condition -> this is the instance condition
      conditionMap.keys.head
    } else {
      // since we don't have a distinct state, we remove states where all tasks have to agree on
      // and search for a distinct state
      val distinctCondition = Instance.AllInstanceConditions.foldLeft(conditionMap) { (ds, status) => ds - status }
      Instance.DistinctInstanceConditions.find(distinctCondition.contains).getOrElse {
        // if no distinct condition is found all tasks are in different AllInstanceConditions
        // we pick the first matching one
        Instance.AllInstanceConditions.find(conditionMap.contains).getOrElse {
          // if we come here, something is wrong, since we covered all existing states
          Instance.log.error(s"Could not compute new instance condition for condition map: $conditionMap")
          Condition.Unknown
        }
      }
    }

    val healthy = computeHealth(tasks.toVector)
    maybeOldState match {
      case Some(state) if state.condition == condition && state.healthy == healthy => state
      case _ => InstanceState(condition, timestamp, healthy)
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

  implicit class InstanceConditionComparison(val instance: Instance) extends AnyVal {
    def isReserved: Boolean = instance.state.condition == Condition.Reserved
    def isCreated: Boolean = instance.state.condition == Condition.Created
    def isError: Boolean = instance.state.condition == Condition.Error
    def isFailed: Boolean = instance.state.condition == Condition.Failed
    def isFinished: Boolean = instance.state.condition == Condition.Finished
    def isKilled: Boolean = instance.state.condition == Condition.Killed
    def isKilling: Boolean = instance.state.condition == Condition.Killing
    def isRunning: Boolean = instance.state.condition == Condition.Running
    def isStaging: Boolean = instance.state.condition == Condition.Staging
    def isStarting: Boolean = instance.state.condition == Condition.Starting
    def isUnreachable: Boolean = instance.state.condition == Condition.Unreachable
    def isGone: Boolean = instance.state.condition == Condition.Gone
    def isUnknown: Boolean = instance.state.condition == Condition.Unknown
    def isDropped: Boolean = instance.state.condition == Condition.Dropped
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
  implicit val agentFormat: Format[AgentInfo] = Json.format[AgentInfo]
  implicit val idFormat: Format[Instance.Id] = Json.format[Instance.Id]
  implicit val instanceConditionFormat: Format[Condition] = Json.format[Condition]
  implicit val instanceStateFormat: Format[InstanceState] = Json.format[InstanceState]
  implicit val instanceJsonFormat: Format[Instance] = Json.format[Instance]
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
  def apply(task: Task): Instance = {
    val since = task.status.startedAt.getOrElse(task.status.stagedAt)
    val tasksMap = Map(task.taskId -> task)
    val state = Instance.newInstanceState(None, tasksMap, since)

    new Instance(task.taskId.instanceId, task.agentInfo, state, tasksMap, task.runSpecVersion)
  }
}
