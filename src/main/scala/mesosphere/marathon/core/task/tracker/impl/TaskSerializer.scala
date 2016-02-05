package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * Converts between [[Task]] objects
  * and their serialized representation: MarathonTask.
  */
object TaskSerializer {
  import scala.collection.JavaConverters._

  def taskState(marathonTask: MarathonTask): Task = {

    def required[T](name: String, maybeValue: Option[T]): T = {
      maybeValue.getOrElse(throw new IllegalArgumentException(s"task[${marathonTask.getId}]: $name must be set"))
    }

    def opt[T](
      hasAttribute: MarathonTask => Boolean, getAttribute: MarathonTask => T): Option[T] = {

      if (hasAttribute(marathonTask)) {
        Some(getAttribute(marathonTask))
      }
      else {
        None
      }
    }

    def agentInfo: Task.AgentInfo = {
      Task.AgentInfo(
        host = required("host", opt(_.hasHost, _.getHost)),
        agentId = opt(_.hasSlaveId, _.getSlaveId).map(_.getValue),
        attributes = marathonTask.getAttributesList.iterator().asScala.toVector
      )
    }

    def reservationWithVolume: Option[Task.ReservationWithVolume.type] = {
      if (marathonTask.getReservationWithVolumeId) {
        Some(Task.ReservationWithVolume)
      }
      else {
        None
      }
    }

    def launchedTask: Option[Task.LaunchedTask] = {
      if (marathonTask.hasStagedAt) {
        Some(
          Task.LaunchedTask(
            appVersion = Timestamp(marathonTask.getVersion),
            status = Task.TaskStatus(
              stagedAt = Timestamp(marathonTask.getStagedAt),
              startedAt = if (marathonTask.hasStartedAt) Some(Timestamp(marathonTask.getStartedAt)) else None,
              status = opt(_.hasStatus, _.getStatus)
            ),
            networking = if (marathonTask.getPortsCount != 0) {
              Task.HostPorts(marathonTask.getPortsList.iterator().asScala.map(_.intValue()).toVector)
            }
            else if (marathonTask.getNetworksCount != 0) {
              Task.NetworkInfoList(marathonTask.getNetworksList.asScala)
            }
            else {
              Task.NoNetworking
            }
          )
        )
      }
      else {
        None
      }
    }

    Task(
      taskId = Task.Id(marathonTask.getId),
      agentInfo = agentInfo,
      reservationWithVolume = reservationWithVolume,
      launchCounter = marathonTask.getLaunchCounter,
      launchedTask = launchedTask
    )
  }

  def marathonTask(taskState: Task): MarathonTask = {
    val builder = MarathonTask.newBuilder()

    builder.setId(taskState.taskId.id)

    import taskState.agentInfo
    builder.setHost(agentInfo.host)
    agentInfo.agentId.foreach { agentId =>
      builder.setSlaveId(MesosProtos.SlaveID.newBuilder().setValue(agentId))
    }
    builder.addAllAttributes(agentInfo.attributes.asJava)

    taskState.reservationWithVolume.foreach(reservation => builder.setReservationWithVolumeId(true))

    builder.setLaunchCounter(taskState.launchCounter)

    taskState.launchedTask.foreach { launchedTask =>
      builder.setVersion(launchedTask.appVersion.toString)
      builder.setStagedAt(launchedTask.status.stagedAt.toDateTime.getMillis)
      launchedTask.status.startedAt.foreach(startedAt => builder.setStartedAt(startedAt.toDateTime.getMillis))
      launchedTask.status.status.foreach(status => builder.setStatus(status))
      launchedTask.networking match {
        case Task.HostPorts(hostPorts) =>
          builder.addAllPorts(hostPorts.view.map(Integer.valueOf(_)).asJava)
        case Task.NetworkInfoList(networkInfoList) =>
          builder.addAllNetworks(networkInfoList.asJava)
        case Task.NoNetworking => // nothing
      }

    }

    builder.build()
  }
}
