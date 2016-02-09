package mesosphere.marathon.core.task

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.Task.Launched
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.tasks.TaskIdUtil
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * The state for launching a task. This might be a launched task or a reservation for launching a task or both.
  */
case class Task(
    taskId: Task.Id,
    agentInfo: Task.AgentInfo,
    reservationWithVolume: Option[Task.ReservationWithVolume.type] = None,
    launchCounter: Long = 0,
    launched: Option[Task.Launched] = None) {

  def appId: PathId = taskId.appId

  /**
    * Legacy conversion to MarathonTask. Cache result to speed up repeated uses.
    * Should be removed before releasing 0.16.
    */
  lazy val marathonTask: MarathonTask = TaskSerializer.marathonTask(this)

  def withAgentInfo(update: Task.AgentInfo => Task.AgentInfo): Task = copy(agentInfo = update(agentInfo))

  def withLaunched(update: Launched => Launched): Task =
    copy(launched = launched.map(update))

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
  case class Id(idString: String) {
    lazy val mesosTaskId: MesosProtos.TaskID = MesosProtos.TaskID.newBuilder().setValue(idString).build()
    lazy val appId: PathId = TaskIdUtil.appId(idString)
    override def toString: String = s"task [$idString]"
  }

  object Id {
    def apply(mesosTaskId: MesosProtos.TaskID): Id = new Id(mesosTaskId.getValue)

    def forApp(appId: PathId): Id = Task.Id(TaskIdUtil.newTaskId(appId))
  }

  /**
    * Represents a reservation for all resources that are needed for launching a task
    * and a persistent volume.
    *
    * The volume ID corresponds to the task ID for two reasons:
    *
    * * it saves storage space
    * * we already have the infrastructure to look for a specific task ID, we do not have
    *   to build a second one to look things up for a volume ID.
    */
  case object ReservationWithVolume

  /**
    * Represents a task which has been launched (i.e. sent to Mesos for launching).
    */
  case class Launched(
      appVersion: Timestamp,
      status: Status,
      networking: Networking) {

    def withStatus(update: Status => Status): Launched = copy(status = update(status))

    def withMesosStatus(mesosStatus: MesosProtos.TaskStatus): Launched = {
      copy(status = status.copy(mesosStatus = Some(mesosStatus)))
    }

    def hasStartedRunning: Boolean = status.startedAt.isDefined

    def ports: Iterable[Int] = networking match {
      case HostPorts(ports) => ports
      case _                => Iterable.empty
    }

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
  sealed trait Networking

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
  }
  object NetworkInfoList {
    def apply(networkInfoList: MesosProtos.NetworkInfo*): NetworkInfoList = NetworkInfoList(networkInfoList)
  }

  case object NoNetworking extends Networking
}
