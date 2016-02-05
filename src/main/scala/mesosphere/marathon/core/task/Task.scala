package mesosphere.marathon.core.task

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.tasks.TaskIdUtil
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * The state for launching a task. This might be a launched task or a reservation for launching a task or both.
  */
case class Task(
    taskId: Task.Id,
    agentInfo: Task.AgentInfo,
    reservationWithVolume: Option[Task.ReservationWithVolume.type],
    launchCounter: Long,
    launchedTask: Option[Task.LaunchedTask]) {
  /**
    * Legacy conversion to MarathonTask. Cache result to speed up repeated uses.
    * Should be removed before releasing 0.16.
    */
  lazy val marathonTask: MarathonTask = TaskSerializer.marathonTask(this)
}

object Task {
  case class Id(id: String) {
    lazy val appId = TaskIdUtil.appId(id)
    override def toString: String = s"task [$id]"
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
  case class LaunchedTask(
    appVersion: Timestamp,
    status: TaskStatus,
    networking: Networking)

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
    */
  case class TaskStatus(
    stagedAt: Timestamp,
    startedAt: Option[Timestamp],
    status: Option[MesosProtos.TaskStatus])

  /** Info on how to reach the task in the network. */
  sealed trait Networking

  /** The task is reachable via host ports which are bound to [[AgentInfo#host]]. */
  case class HostPorts(ports: Iterable[Int]) extends Networking

  /**
    * The task has been launched with one-IP-per-task settings. The ports can be discovered
    * by inspecting the [[mesosphere.marathon.state.DiscoveryInfo]] in the [[mesosphere.marathon.state.AppDefinition]].
    */
  case class NetworkInfoList(networkInfoList: Iterable[MesosProtos.NetworkInfo]) extends Networking {
    import scala.collection.JavaConverters._
    def addresses: Iterable[MesosProtos.NetworkInfo.IPAddress] = networkInfoList.flatMap(_.getIpAddressesList.asScala)
  }

  case object NoNetworking extends Networking
}
