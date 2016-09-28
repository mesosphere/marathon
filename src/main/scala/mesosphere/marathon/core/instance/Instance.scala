package mesosphere.marathon.core.instance

import java.util.Base64

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.Protos
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.{ TaskUpdateEffect, TaskUpdateOperation }
import mesosphere.marathon.state.{ MarathonState, PathId, Timestamp }
import mesosphere.mesos.Placed
import org.apache._
import org.apache.mesos.Protos.Attribute
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.{ Reads, Writes }
// TODO: Remove timestamp format
import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
import play.api.libs.json.{ Format, JsResult, JsString, JsValue, Json }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

// TODO: remove MarathonState stuff once legacy persistence is gone
case class Instance(
    instanceId: Instance.Id,
    agentInfo: Instance.AgentInfo,
    state: InstanceState,
    tasksMap: Map[Task.Id, Task]) extends MarathonState[Protos.Json, Instance] with Placed {

  // TODO(PODS): check consumers of this def and see if they can use the map instead
  val tasks = tasksMap.values
  // TODO(PODS): make this a case class ctor argument and move out of Instance.Status
  val runSpecVersion: Timestamp = state.version
  val runSpecId: PathId = instanceId.runSpecId
  val isLaunched: Boolean = tasksMap.valuesIterator.forall(task => task.launched.isDefined)

  import Instance.eventsGenerator

  // TODO(PODS): verify functionality and reduce complexity
  def update(op: InstanceUpdateOperation): InstanceUpdateEffect = {
    // TODO(PODS): implement logic:
    // - propagate the change to the task
    // - calculate the new instance status based on the state of the task

    // TODO(PODS): make sure state transitions are allowed. maybe implement a simple state machine?
    op match {
      case InstanceUpdateOperation.MesosUpdate(instance, status, mesosStatus, now) =>
        val taskId = Task.Id(mesosStatus.getTaskId)
        tasks.find(_.taskId == taskId).map { task =>
          val taskEffect = task.update(TaskUpdateOperation.MesosUpdate(status, mesosStatus, now))
          taskEffect match {
            case TaskUpdateEffect.Update(newTaskState) =>
              val updated: Instance = updatedInstance(newTaskState, now)
              val events = eventsGenerator.events(status, updated, Some(task), now)
              if (updated.tasksMap.valuesIterator.forall(_.isTerminal)) {
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

      case InstanceUpdateOperation.LaunchOnReservation(_, version, timestamp, status, hostPorts) =>
        if (this.isReserved) {
          require(tasksMap.size == 1, "Residency is not yet implemented for task groups")

          // TODO(PODS): make this work for taskGroups
          val task = tasksMap.values.head
          val taskEffect = task.update(TaskUpdateOperation.LaunchOnReservation(runSpecVersion, status, hostPorts))
          taskEffect match {
            case TaskUpdateEffect.Update(updatedTask) =>
              val updated = this.copy(
                state = state.copy(
                  status = InstanceStatus.Staging,
                  since = timestamp,
                  version = version
                ),
                tasksMap = tasksMap.updated(task.taskId, updatedTask)
              )
              val events = eventsGenerator.events(updated.state.status, updated, task = None, timestamp)
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
          val events = eventsGenerator.events(state.status, this, task = None, Timestamp.now())
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
    copy(tasksMap = updatedTasks, state = newInstanceState(updatedTasks, now))
  }

  @SuppressWarnings(Array("TraversableHead"))
  private[instance] def newInstanceState(newTaskMap: Map[Task.Id, Task], timestamp: Timestamp): InstanceState = {
    val tasks = newTaskMap.values

    //compute the new instance status
    val stateMap = tasks.groupBy(_.status.taskStatus)
    val status = if (stateMap.size == 1) {
      // all tasks have the same status -> this is the instance status
      stateMap.keys.head
    } else {
      // since we don't have a distinct state, we remove states where all tasks have to agree on
      // and search for a distinct state
      val distinctStates = Instance.AllInstanceStatuses.foldLeft(stateMap) { (ds, status) => ds - status }
      Instance.DistinctInstanceStatuses.find(distinctStates.contains).getOrElse {
        // if no distinct state is found all tasks are in different AllInstanceStatuses
        // we pick the first matching one
        Instance.AllInstanceStatuses.find(stateMap.contains).getOrElse {
          // if we come here, something is wrong, since we covered all existing states
          Instance.log.error(s"Could not compute new instance state for state map: $stateMap")
          InstanceStatus.Unknown
        }

      }
    }

    // an instance is healthy, if all tasks are healthy
    // an instance is unhealthy, if at least one task is unhealthy
    // otherwise the health is unknown
    val healthy = {
      val tasksHealth = tasks.map(_.status.mesosStatus.flatMap(p => if (p.hasHealthy) Some(p.getHealthy) else None))
      if (tasksHealth.exists(_.exists(healthy => !healthy))) Some(false)
      else if (tasksHealth.forall(_.exists(identity))) Some(true)
      else None
    }

    if (this.state.status == status && this.state.healthy == healthy) this.state
    else InstanceState(status, timestamp, this.state.version, healthy)
  }
}

object Instance {
  // required for legacy store, remove when legacy storage is removed.
  def apply(): Instance = {
    new Instance(Instance.Id(""), AgentInfo("", None, Nil),
      InstanceState(InstanceStatus.Unknown, Timestamp.zero, Timestamp.zero, healthy = None), Map.empty[Task.Id, Task])
  }

  private val eventsGenerator = InstanceChangedEventsGenerator
  private val log: Logger = LoggerFactory.getLogger(classOf[Instance])

  /**
    * An instance can only have this status, if all tasks of the intance have this status.
    * The order of the status is important.
    * If 2 tasks are Running and 2 tasks already Finished, the final status is Running.
    */
  private val AllInstanceStatuses: Seq[InstanceStatus] = Seq(
    InstanceStatus.Created,
    InstanceStatus.Reserved,
    InstanceStatus.Running,
    InstanceStatus.Finished,
    InstanceStatus.Killed
  )

  /**
    * An instance has this status, if at least one tasks of the instance has this status.
    * The order of the status is important.
    * If one task is Error and one task is Staging, the instance status is Error.
    */
  private val DistinctInstanceStatuses: Seq[InstanceStatus] = Seq(
    InstanceStatus.Error,
    InstanceStatus.Failed,
    InstanceStatus.Gone,
    InstanceStatus.Dropped,
    InstanceStatus.Unreachable,
    InstanceStatus.Killing,
    InstanceStatus.Starting,
    InstanceStatus.Staging,
    InstanceStatus.Unknown
  )

  def instancesById(tasks: Iterable[Instance]): Map[Instance.Id, Instance] =
    tasks.iterator.map(task => task.instanceId -> task).toMap

  // TODO(PODS-BLOCKER) ju remove apply
  def apply(task: Task): Instance = {
    def defaultVersion: Timestamp = {
      // TODO(PODS): fix this
      log.error("A default Timestamp.zero breaks things!")
      Timestamp.zero
    }
    new Instance(task.taskId.instanceId, task.agentInfo,
      InstanceState(
        status = task.status.taskStatus,
        since = task.status.startedAt.getOrElse(task.status.stagedAt),
        version = task.version.getOrElse(defaultVersion),
        healthy = None),
      Map(task.taskId -> task))
  }
  case class InstanceState(status: InstanceStatus, since: Timestamp, version: Timestamp, healthy: Option[Boolean])

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
      attributes = offer.getAttributesList.asScala.toVector
    )
  }

  implicit class InstanceStatusComparison(val instance: Instance) extends AnyVal {
    def isReserved: Boolean = instance.state.status == InstanceStatus.Reserved
    def isCreated: Boolean = instance.state.status == InstanceStatus.Created
    def isError: Boolean = instance.state.status == InstanceStatus.Error
    def isFailed: Boolean = instance.state.status == InstanceStatus.Failed
    def isFinished: Boolean = instance.state.status == InstanceStatus.Finished
    def isKilled: Boolean = instance.state.status == InstanceStatus.Killed
    def isKilling: Boolean = instance.state.status == InstanceStatus.Killing
    def isRunning: Boolean = instance.state.status == InstanceStatus.Running
    def isStaging: Boolean = instance.state.status == InstanceStatus.Staging
    def isStarting: Boolean = instance.state.status == InstanceStatus.Starting
    def isUnreachable: Boolean = instance.state.status == InstanceStatus.Unreachable
    def isGone: Boolean = instance.state.status == InstanceStatus.Gone
    def isUnknown: Boolean = instance.state.status == InstanceStatus.Unknown
    def isDropped: Boolean = instance.state.status == InstanceStatus.Dropped
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
  implicit val instanceStatusFormat: Format[InstanceStatus] = Json.format[InstanceStatus]
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
