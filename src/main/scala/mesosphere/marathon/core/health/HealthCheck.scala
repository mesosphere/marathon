package mesosphere.marathon.core.health

import com.wix.accord._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.duration._

sealed trait HealthCheck {
  def gracePeriod: FiniteDuration

  def interval: FiniteDuration

  def timeout: FiniteDuration

  def maxConsecutiveFailures: Int

  def toProto: Protos.HealthCheckDefinition

  protected def protoBuilder: Protos.HealthCheckDefinition.Builder =
    Protos.HealthCheckDefinition.newBuilder
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)
}

sealed trait HealthCheckWithPort {
  this: HealthCheck =>
  def portIndex: Option[Int]

  def port: Option[Int]
}

object HealthCheckWithPort {
  val DefaultPortIndex = None
  val DefaultPort = None

  import mesosphere.marathon.api.v2.Validation.isTrue

  implicit val Validator: Validator[HealthCheckWithPort] =
    isTrue("HealthCheck must specify either a port or a portIndex") { hc =>
      hc.portIndex.isDefined ^ hc.port.isDefined
    }
}

sealed trait MarathonHealthCheck extends HealthCheckWithPort {
  this: HealthCheck =>
  def portIndex: Option[Int]

  def port: Option[Int]

  def effectivePort(app: AppDefinition, task: Task): Option[Int] = {
    def portViaIndex: Option[Int] = portIndex.flatMap { portIndex =>
      app.portAssignments(task).flatMap(_.lift(portIndex)).map(_.effectivePort)
    }

    port.orElse(portViaIndex)
  }
}

sealed trait MesosHealthCheck {
  this: HealthCheck =>
  def gracePeriod: FiniteDuration

  def interval: FiniteDuration

  def timeout: FiniteDuration

  def maxConsecutiveFailures: Int

  def toMesos(portAssignments: Seq[PortAssignment] = Seq.empty): MesosProtos.HealthCheck
}

sealed trait MesosHealthCheckWithPorts extends HealthCheckWithPort {
  this: HealthCheck =>
  def effectivePort(portAssignments: Seq[PortAssignment]): Int = {
    port match {
      case Some(port) => port
      case None =>
        val portAssignment = portIndex.map(portAssignments(_))
        portAssignment.flatMap(_.containerPort).getOrElse(portAssignment.flatMap(_.hostPort).get)
    }
  }
}

case class MarathonHttpHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  portIndex: Option[Int] = HealthCheckWithPort.DefaultPortIndex,
  port: Option[Int] = HealthCheckWithPort.DefaultPort,
  path: Option[String] = MarathonHttpHealthCheck.DefaultPath,
  protocol: Protocol = MarathonHttpHealthCheck.DefaultProtocol,
  ignoreHttp1xx: Boolean = MarathonHttpHealthCheck.DefaultIgnoreHttp1xx)
    extends HealthCheck with MarathonHealthCheck {
  override def toProto: Protos.HealthCheckDefinition = {
    val builder = protoBuilder
      .setProtocol(protocol)
      .setIgnoreHttp1Xx(this.ignoreHttp1xx)

    path.foreach(builder.setPath)

    portIndex.foreach(builder.setPortIndex)
    port.foreach(builder.setPort)

    builder.build
  }
}

object MarathonHttpHealthCheck {
  val DefaultPath = None
  val DefaultIgnoreHttp1xx = false
  val DefaultProtocol = Protocol.HTTP

  def mergeFromProto(proto: Protos.HealthCheckDefinition): MarathonHttpHealthCheck =
    MarathonHttpHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      ignoreHttp1xx = proto.getIgnoreHttp1Xx,
      path = if (proto.hasPath) Some(proto.getPath) else None,
      portIndex =
      if (proto.hasPortIndex)
        Some(proto.getPortIndex)
      else if (!proto.hasPort)
        Some(0) // backward compatibility, this used to be the default value in marathon.proto
      else
        None,
      port = if (proto.hasPort) Some(proto.getPort) else None,
      protocol = proto.getProtocol
    )
}

case class MarathonTcpHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  portIndex: Option[Int] = HealthCheckWithPort.DefaultPortIndex,
  port: Option[Int] = HealthCheckWithPort.DefaultPort)
    extends HealthCheck with MarathonHealthCheck {
  override def toProto: Protos.HealthCheckDefinition = {
    val builder = protoBuilder.setProtocol(Protos.HealthCheckDefinition.Protocol.TCP)

    portIndex.foreach(builder.setPortIndex)
    port.foreach(builder.setPort)

    builder.build
  }
}

object MarathonTcpHealthCheck {
  def mergeFromProto(proto: Protos.HealthCheckDefinition): MarathonTcpHealthCheck =
    MarathonTcpHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      portIndex =
      if (proto.hasPortIndex)
        Some(proto.getPortIndex)
      else if (!proto.hasPort)
        Some(0) // backward compatibility, this used to be the default value in marathon.proto
      else
        None,
      port = if (proto.hasPort) Some(proto.getPort) else None
    )
}

case class MesosCommandHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  command: Command)
    extends HealthCheck with MesosHealthCheck {
  override def toProto: Protos.HealthCheckDefinition = {
    protoBuilder
      .setProtocol(Protos.HealthCheckDefinition.Protocol.COMMAND)
      .setCommand(command.toProto)
      .build
  }

  def toMesos(portAssignments: Seq[PortAssignment] = Seq.empty): MesosProtos.HealthCheck = {
    MesosProtos.HealthCheck.newBuilder
      .setType(MesosProtos.HealthCheck.Type.COMMAND)
      .setIntervalSeconds(this.interval.toSeconds.toDouble)
      .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toUnit(SECONDS))
      .setCommand(this.command.toProto)
      .build()
  }
}

object MesosCommandHealthCheck {
  def mergeFromProto(proto: Protos.HealthCheckDefinition): MesosCommandHealthCheck =
    MesosCommandHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      command = Command("").mergeFromProto(proto.getCommand)
    )
}

case class MesosHttpHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  portIndex: Option[Int] = HealthCheckWithPort.DefaultPortIndex,
  port: Option[Int] = HealthCheckWithPort.DefaultPort,
  path: Option[String] = MarathonHttpHealthCheck.DefaultPath,
  protocol: Protocol = MesosHttpHealthCheck.DefaultProtocol)
    extends HealthCheck with MesosHealthCheck with MesosHealthCheckWithPorts {
  require(protocol == Protocol.MESOS_HTTP || protocol == Protocol.MESOS_HTTPS)

  override def toProto: Protos.HealthCheckDefinition = {
    val builder = protoBuilder
      .setProtocol(protocol)

    path.foreach(builder.setPath)

    portIndex.foreach(builder.setPortIndex)
    port.foreach(builder.setPort)

    builder.build
  }

  override def toMesos(portAssignments: Seq[PortAssignment] = Seq.empty): MesosProtos.HealthCheck = {
    val httpInfoBuilder = MesosProtos.HealthCheck.HTTPCheckInfo.newBuilder()
      .setScheme(if (protocol == Protocol.MESOS_HTTP) "http" else "https")
      .setPort(effectivePort(portAssignments))
    path.foreach(httpInfoBuilder.setPath)

    MesosProtos.HealthCheck.newBuilder
      .setType(MesosProtos.HealthCheck.Type.HTTP)
      .setIntervalSeconds(this.interval.toSeconds.toDouble)
      .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toUnit(SECONDS))
      .setHttp(httpInfoBuilder)
      .build()
  }
}

object MesosHttpHealthCheck {
  val DefaultPath = None
  val DefaultProtocol = Protocol.MESOS_HTTP

  def mergeFromProto(proto: Protos.HealthCheckDefinition): MesosHttpHealthCheck =
    MesosHttpHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      path = if (proto.hasPath) Some(proto.getPath) else None,
      portIndex =
      if (proto.hasPortIndex)
        Some(proto.getPortIndex)
      else if (!proto.hasPort)
        Some(0) // backward compatibility, this used to be the default value in marathon.proto
      else
        None,
      port = if (proto.hasPort) Some(proto.getPort) else None,
      protocol = proto.getProtocol
    )
}

case class MesosTcpHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  portIndex: Option[Int] = HealthCheckWithPort.DefaultPortIndex,
  port: Option[Int] = HealthCheckWithPort.DefaultPort)
    extends HealthCheck with MesosHealthCheck with MesosHealthCheckWithPorts {
  override def toProto: Protos.HealthCheckDefinition = {
    val builder = protoBuilder.setProtocol(Protos.HealthCheckDefinition.Protocol.MESOS_TCP)

    portIndex.foreach(builder.setPortIndex)
    port.foreach(builder.setPort)

    builder.build
  }

  override def toMesos(portAssignments: Seq[PortAssignment] = Seq.empty): MesosProtos.HealthCheck = {
    val tcpInfoBuilder = MesosProtos.HealthCheck.TCPCheckInfo.newBuilder().setPort(effectivePort(portAssignments))

    MesosProtos.HealthCheck.newBuilder
      .setType(MesosProtos.HealthCheck.Type.TCP)
      .setIntervalSeconds(this.interval.toSeconds.toDouble)
      .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toUnit(SECONDS))
      .setTcp(tcpInfoBuilder)
      .build()
  }
}

object MesosTcpHealthCheck {
  def mergeFromProto(proto: Protos.HealthCheckDefinition): MesosTcpHealthCheck =
    MesosTcpHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      portIndex =
      if (proto.hasPortIndex)
        Some(proto.getPortIndex)
      else if (!proto.hasPort)
        Some(0) // backward compatibility, this used to be the default value in marathon.proto
      else
        None,
      port = if (proto.hasPort) Some(proto.getPort) else None
    )
}

object HealthCheck {
  val DefaultProtocol = Protocol.HTTP
  // Docker images can take a long time to download, so default to a fairly long wait.
  val DefaultGracePeriod = 5.minutes
  val DefaultInterval = 1.minute
  val DefaultTimeout = 20.seconds
  val DefaultMaxConsecutiveFailures = 3
  val DefaultIgnoreHttp1xx = false
  val DefaultPort = None
  // As soon as an application is started (before a task is launched), we start the health checks for this app.
  // We optimistically set a low value here, for tasks that start really fast
  val DefaultFirstHealthCheckAfter = 5.seconds

  implicit val Validator: Validator[HealthCheck] = new Validator[HealthCheck] {
    override def apply(hc: HealthCheck): Result = {
      hc match {
        case h: HealthCheckWithPort => HealthCheckWithPort.Validator(h)
        case _ => Success
      }
    }
  }

  def fromProto(proto: Protos.HealthCheckDefinition): HealthCheck = {
    proto.getProtocol match {
      case Protocol.COMMAND => MesosCommandHealthCheck.mergeFromProto(proto)
      case Protocol.TCP => MarathonTcpHealthCheck.mergeFromProto(proto)
      case Protocol.HTTP | Protocol.HTTPS => MarathonHttpHealthCheck.mergeFromProto(proto)
      case Protocol.MESOS_TCP => MesosTcpHealthCheck.mergeFromProto(proto)
      case Protocol.MESOS_HTTP | Protocol.MESOS_HTTPS => MesosHttpHealthCheck.mergeFromProto(proto)
    }
  }
}
