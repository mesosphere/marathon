package mesosphere.marathon
package core.instance

import java.util.{Base64, UUID}

import com.fasterxml.uuid.{EthernetAddress, Generators}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.UnreachableInactive
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{MarathonState, PathId, Timestamp, UnreachableDisabled, UnreachableEnabled, UnreachableStrategy, _}
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.OfferUtil
import mesosphere.mesos.Placed
import org.apache._
import org.apache.mesos.Protos.Attribute
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.matching.Regex

// TODO: remove MarathonState stuff once legacy persistence is gone
case class Instance(
    instanceId: Instance.Id,
    agentInfo: Option[Instance.AgentInfo],
    state: InstanceState,
    tasksMap: Map[Task.Id, Task],
    runSpecVersion: Timestamp,
    unreachableStrategy: UnreachableStrategy,
    reservation: Option[Reservation]) extends MarathonState[Protos.Json, Instance] with Placed {

  val runSpecId: PathId = instanceId.runSpecId

  val isReserved: Boolean = state.condition == Condition.Reserved

  def isReservedTerminal: Boolean = isReserved

  /**
    * An instance is scheduled for launching when its goal is to be running but it's not active.
    *
    * Note: A provisioned instance is considered active.
    */
  val isScheduled: Boolean = state.goal == Goal.Running && !isActive && !state.condition.isLost && state.condition != UnreachableInactive

  val isProvisioned: Boolean = state.condition == Condition.Provisioned

  def isKilling: Boolean = state.condition == Condition.Killing
  def isRunning: Boolean = state.condition == Condition.Running
  def isUnreachable: Boolean = state.condition == Condition.Unreachable
  def isUnreachableInactive: Boolean = state.condition == Condition.UnreachableInactive
  def isActive: Boolean = state.condition.isActive
  def hasReservation: Boolean = reservation.isDefined

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

  override def hostname: Option[String] = agentInfo.map(_.host)

  override def attributes: Seq[Attribute] = agentInfo.map(_.attributes).getOrElse(Seq.empty)

  override def zone: Option[String] = agentInfo.flatMap(_.zone)

  override def region: Option[String] = agentInfo.flatMap(_.region)

  /**
    * Factory method for creating provisioned instance from Scheduled instance for apps
    * @return new instance in a provisioned state
    */
  def provisioned(agentInfo: Instance.AgentInfo, networkInfo: core.task.state.NetworkInfo, app: AppDefinition, now: Timestamp, taskId: Task.Id): Instance = {
    require(isScheduled, s"Instance '${instanceId}' must not be in state '${state.condition}'. Scheduled instance is required to create provisioned instance.")

    this.copy(
      agentInfo = Some(agentInfo),
      state = Instance.InstanceState(Condition.Provisioned, now, None, None, Goal.Running),
      tasksMap = Map(taskId -> Task(
        taskId = taskId,
        runSpecVersion = app.version,
        status = Task.Status(
          stagedAt = now,
          condition = Condition.Provisioned,
          networkInfo = networkInfo
        ))),
      runSpecVersion = app.version
    )
  }

  /**
    * Factory method for creating provisioned instance from Scheduled instance for pods
    * @return new instance in a provisioned state
    */
  def provisioned(agentInfo: Instance.AgentInfo, hostPorts: Seq[Option[Int]], pod: PodDefinition, taskIds: Seq[Task.Id], now: Timestamp): Instance = {
    require(isScheduled, s"Instance '${instanceId}' must not be in state '${state.condition}'. Scheduled instance is required to create provisioned instance.")

    val taskNetworkInfos = Instance.podTaskNetworkInfos(pod, agentInfo, taskIds, hostPorts)

    this.copy(
      agentInfo = Some(agentInfo),
      state = Instance.InstanceState(Condition.Provisioned, now, None, None, Goal.Running),
      tasksMap = taskIds.map { taskId =>
        // the task level host ports are needed for fine-grained status/reporting later on
        val networkInfo = taskNetworkInfos.getOrElse(
          taskId,
          throw new IllegalStateException("failed to retrieve a task network info"))
        val task = Task(
          taskId = taskId,
          runSpecVersion = pod.version,
          status = Task.Status(stagedAt = now, condition = Condition.Provisioned, networkInfo = networkInfo)
        )
        task.taskId -> task
      }(collection.breakOut),
      runSpecVersion = pod.version
    )
  }
}

object Instance {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  def instancesById(instances: Seq[Instance]): Map[Instance.Id, Instance] =
    instances.map(instance => instance.instanceId -> instance)(collection.breakOut)

  object Running {
    def unapply(instance: Instance): Option[Tuple3[Instance.Id, Instance.AgentInfo, Map[Task.Id, Task]]] = instance match {
      case Instance(instanceId, Some(agentInfo), InstanceState(Condition.Running, _, _, _, _), tasksMap, _, _, _) =>
        Some((instanceId, agentInfo, tasksMap))
      case _ =>
        Option.empty[Tuple3[Instance.Id, Instance.AgentInfo, Map[Task.Id, Task]]]
    }
  }

  /**
    * Factory method for an instance in a [[Condition.Scheduled]] state.
    *
    * @param runSpec The run spec the instance will be started for.
    * @param instanceId The id of the new instance.
    * @return An instance in the scheduled state.
    */
  def scheduled(runSpec: RunSpec, instanceId: Instance.Id): Instance = {
    val state = InstanceState(Condition.Scheduled, Timestamp.now(), None, None, Goal.Running)
    Instance(instanceId, None, state, Map.empty, runSpec.version, runSpec.unreachableStrategy, None)
  }

  /*
   * Factory method for an instance in a [[Condition.Scheduled]] state.
   *
   * @param runSpec The run spec the instance will be started for.
   * @return An instance in the scheduled state.
   */
  def scheduled(runSpec: RunSpec): Instance = scheduled(runSpec, Id.forRunSpec(runSpec.id))

  /**
    * Creates new instance that is scheduled and has reservation (for resident run specs)
    */
  def scheduled(scheduledInstance: Instance, reservation: Reservation, agentInfo: AgentInfo): Instance = {
    scheduledInstance.copy(
      reservation = Some(reservation),
      agentInfo = Some(agentInfo))
  }

  private def podTaskNetworkInfos(
    pod: PodDefinition,
    agentInfo: Instance.AgentInfo,
    taskIDs: Seq[Task.Id],
    hostPorts: Seq[Option[Int]]
  ): Map[Task.Id, NetworkInfo] = {

    val reqPortsByCTName: Seq[(String, Option[Int])] = pod.containers.flatMap { ct =>
      ct.endpoints.map { ep =>
        ct.name -> ep.hostPort
      }
    }

    val totalRequestedPorts = reqPortsByCTName.size
    require(totalRequestedPorts == hostPorts.size, s"expected that number of allocated ports ${hostPorts.size}" +
      s" would equal the number of requested host ports $totalRequestedPorts")

    require(!hostPorts.flatten.contains(0), "expected that all dynamic host ports have been allocated")

    val allocPortsByCTName: Seq[(String, Int)] = reqPortsByCTName.zip(hostPorts).collect {
      case ((name, Some(_)), Some(allocatedPort)) => name -> allocatedPort
    }(collection.breakOut)

    taskIDs.map { taskId =>
      // the task level host ports are needed for fine-grained status/reporting later on
      val taskHostPorts: Seq[Int] = taskId.containerName.map { ctName =>
        allocPortsByCTName.withFilter { case (name, port) => name == ctName }.map(_._2)
      }.getOrElse(Seq.empty[Int])

      val networkInfo = NetworkInfo(agentInfo.host, taskHostPorts, ipAddresses = Nil)
      taskId -> networkInfo
    }(collection.breakOut)
  }

  /**
    * Describes the state of an instance which is an accumulation of task states.
    *
    * @param condition The condition of the instance such as running, killing, killed.
    * @param since Denotes when the state was *first* update to the current condition.
    * @param activeSince Denotes the first task startedAt timestamp if any.
    * @param healthy Tells if all tasks run healthily if health checks have been enabled.
    */
  case class InstanceState(condition: Condition, since: Timestamp, activeSince: Option[Timestamp], healthy: Option[Boolean], goal: Goal)

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

      //From here on all tasks are only in one of the following states
      Condition.Provisioned,
      Condition.Reserved,
      Condition.Running,
      Condition.Finished,
      Condition.Killed
    ).indexOf(_)

    /**
      * Construct a new InstanceState.
      *
      * @param maybeOldInstanceState The old state instance if any.
      * @param newTaskMap    New tasks and their status that form the update instance.
      * @param now           Timestamp of update.
      * @return new InstanceState
      */
    def apply(
      maybeOldInstanceState: Option[InstanceState],
      newTaskMap: Map[Task.Id, Task],
      now: Timestamp,
      unreachableStrategy: UnreachableStrategy,
      goal: Goal): InstanceState = {

      val tasks = newTaskMap.values

      // compute the new instance condition
      val condition = conditionFromTasks(tasks, now, unreachableStrategy)

      val active: Option[Timestamp] = activeSince(tasks)

      val healthy = computeHealth(tasks.toVector)
      maybeOldInstanceState match {
        case Some(state) if state.condition == condition && state.healthy == healthy => state
        case _ => InstanceState(condition, now, active, healthy, goal)
      }
    }

    /**
      * @return condition for instance with tasks.
      */
    def conditionFromTasks(tasks: Iterable[Task], now: Timestamp, unreachableStrategy: UnreachableStrategy): Condition = {
      if (tasks.isEmpty) {
        Condition.Unknown
      } else {
        // The smallest Condition according to conditionOrdering is the condition for the whole instance.
        tasks.view.map(_.status.condition).minBy(conditionHierarchy) match {
          case Condition.Unreachable if shouldBecomeInactive(tasks, now, unreachableStrategy) =>
            Condition.UnreachableInactive
          case condition =>
            condition
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
      * @return if one of tasks has been UnreachableInactive for more than unreachableInactiveAfter.
      */
    def shouldBecomeInactive(tasks: Iterable[Task], now: Timestamp, unreachableStrategy: UnreachableStrategy): Boolean =
      unreachableStrategy match {
        case UnreachableDisabled => false
        case unreachableEnabled: UnreachableEnabled =>
          tasks.exists(_.isUnreachableExpired(now, unreachableEnabled.inactiveAfter))
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

  sealed trait Prefix {
    val value: String
    override def toString: String = value
  }
  case object PrefixInstance extends Prefix {
    override val value = "instance-"
  }
  case object PrefixMarathon extends Prefix {
    override val value = "marathon-"
  }
  object Prefix {
    def fromString(prefix: String) = {
      if (prefix == PrefixInstance.value) PrefixInstance
      else PrefixMarathon
    }
  }

  case class Id(val runSpecId: PathId, val prefix: Prefix, uuid: UUID) extends Ordered[Id] {
    lazy val safeRunSpecId = runSpecId.safePath
    lazy val executorIdString: String = prefix + safeRunSpecId + "." + uuid

    // Must match Id.InstanceIdRegex
    // TODO: Unit test against regex
    lazy val idString = safeRunSpecId + "." + prefix + uuid

    /**
      * String representation used for logging and debugging. Should *not* be used for Mesos task ids. Use `idString`
      * instead.
      *
      * @return String representation of id.
      */
    override def toString: String = s"instance [$idString]"

    override def compare(that: Instance.Id): Int =
      if (this.getClass == that.getClass)
        idString.compare(that.idString)
      else this.compareTo(that)
  }

  object Id {
    // Regular expression to extract runSpecId from instanceId
    // instanceId = $runSpecId.(instance-|marathon-)$uuid
    val InstanceIdRegex: Regex = """^(.+)\.(instance-|marathon-)([^\.]+)$""".r

    private val ReservationIdRegex = """^(.+)([\._])([^_\.]+)$""".r

    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def forRunSpec(id: PathId): Id = Instance.Id(id, PrefixInstance, uuidGenerator.generate())

    def fromIdString(idString: String): Instance.Id = {
      idString match {
        case InstanceIdRegex(safeRunSpecId, prefix, uuid) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          Id(runSpec, Prefix.fromString(prefix), UUID.fromString(uuid))
        case _ => throw new MatchError(s"instance id $idString is not a valid identifier")
      }
    }

    /**
      * Extract instance id from reservation ids which were generated by [[Task.Id.reservationId]]
      *
      * @param reservationId The raw reservation id.
      * @return The instance identifier belonging to the reservation.
      */
    def fromReservationId(reservationId: String): Instance.Id = {
      reservationId match {
        case InstanceIdRegex(safeRunSpecId, prefix, uuid) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          Id(runSpec, Prefix.fromString(prefix), UUID.fromString(uuid))
        case ReservationIdRegex(safeRunSpecId, separator, uuid) =>
          val runSpec = PathId.fromSafePath(safeRunSpecId)
          Id(runSpec, PrefixMarathon, UUID.fromString(uuid))
        case _ => throw new MatchError(s"reservation id $reservationId does not include a valid instance identifier")
      }

    }
  }

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
      host: String,
      agentId: Option[String],
      region: Option[String],
      zone: Option[String],
      attributes: Seq[Attribute])

  object AgentInfo {
    def apply(offer: org.apache.mesos.Protos.Offer): AgentInfo = AgentInfo(
      host = offer.getHostname,
      agentId = Some(offer.getSlaveId.getValue),
      region = OfferUtil.region(offer),
      zone = OfferUtil.zone(offer),
      attributes = offer.getAttributesList.toIndexedSeq
    )
  }

  /**
    * Marathon has requested (or will request) that this instance be launched by Mesos.
    *
    * @param instance is the thing that Marathon wants to launch
    */
  case class LaunchRequest(instance: Instance)

  implicit class LegacyInstanceImprovement(val instance: Instance) extends AnyVal {
    /** Convenient access to a legacy instance's only task */
    def appTask: Task = instance.tasksMap.headOption.map(_._2).getOrElse(
      throw new IllegalStateException(s"No task in ${instance.instanceId}"))
  }

  implicit object AttributeFormat extends Format[Attribute] {
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

  // host: String,
  // agentId: Option[String],
  // region: String,
  // zone: String,
  // attributes: Seq[mesos.Protos.Attribute])
  // private val agentFormatWrites: Writes[AgentInfo] = Json.format[AgentInfo]
  private val agentReads: Reads[AgentInfo] = (
    (__ \ "host").read[String] ~
    (__ \ "agentId").readNullable[String] ~
    (__ \ "region").readNullable[String] ~
    (__ \ "zone").readNullable[String] ~
    (__ \ "attributes").read[Seq[mesos.Protos.Attribute]]
  )(AgentInfo(_, _, _, _, _))

  implicit val agentFormat: Format[AgentInfo] = Format(agentReads, Json.writes[AgentInfo])

  // TODO(karsten): Someone with more patience for Play Json is happily invited to change the parsing.
  implicit object InstanceIdFormat extends Format[Instance.Id] {
    override def reads(json: JsValue): JsResult[Id] = {
      (json \ "idString") match {
        case JsDefined(JsString(id)) => JsSuccess(Instance.Id.fromIdString(id), JsPath \ "idString")
        case _ => JsError(JsPath \ "idString", "Could not parse instance id.")
      }
    }

    override def writes(id: Id): JsValue = {
      Json.obj("idString" -> id.idString)
    }
  }

  implicit val instanceConditionFormat: Format[Condition] = Condition.conditionFormat

  implicit val instanceStateFormat: Format[InstanceState] = Json.format[InstanceState]

  implicit val reservationFormat: Format[Reservation] = Reservation.reservationFormat

  implicit val instanceJsonWrites: Writes[Instance] = {
    (
      (__ \ "instanceId").write[Instance.Id] ~
      (__ \ "agentInfo").writeNullable[AgentInfo] ~
      (__ \ "tasksMap").write[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").write[Timestamp] ~
      (__ \ "state").write[InstanceState] ~
      (__ \ "unreachableStrategy").write[raml.UnreachableStrategy] ~
      (__ \ "reservation").writeNullable[Reservation]
    ) { (i) =>
        val unreachableStrategy = Raml.toRaml(i.unreachableStrategy)
        (i.instanceId, i.agentInfo, i.tasksMap, i.runSpecVersion, i.state, unreachableStrategy, i.reservation)
      }
  }

  implicit val instanceJsonReads: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Instance.Id] ~
      (__ \ "agentInfo").readNullable[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState] ~
      (__ \ "unreachableStrategy").readNullable[raml.UnreachableStrategy] ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, maybeUnreachableStrategy, reservation) =>
        val unreachableStrategy = maybeUnreachableStrategy.
          map(Raml.fromRaml(_)).getOrElse(UnreachableStrategy.default())
        new Instance(instanceId, agentInfo, state, tasksMap, runSpecVersion, unreachableStrategy, reservation)
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
