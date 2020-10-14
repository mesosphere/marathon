package mesosphere.marathon
package core.check

import com.wix.accord._

import mesosphere.marathon.Protos.CheckDefinition.Protocol
import mesosphere.marathon.state._
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.duration._

/**
  * Check is a Type to help with type conversions to and from different protos.
  * It mirrors closely to HealthCheck which does the same thing.
  * Check is the most abstract Check.   The appDef works with MesosChecks.
  *
  * toProto takes this data structure into the CheckDefinition proto defined in Marathon used for storage.
  * toMesos takes this data structure into Mesos CheckInfo for working with TaskBuilder
  * Conversations to and from raml is in CheckConversion class.
  */
sealed trait Check {
  def delay: FiniteDuration
  def interval: FiniteDuration
  def timeout: FiniteDuration
  def toProto: Protos.CheckDefinition

  protected def protoBuilder: Protos.CheckDefinition.Builder =
    Protos.CheckDefinition.newBuilder
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setDelaySeconds(this.delay.toSeconds.toInt)
}

sealed trait PortReference extends Product with Serializable {
  def apply(assignments: Seq[PortAssignment]): PortAssignment
  def buildProto(builder: Protos.CheckDefinition.Builder): Unit
}

object PortReference {
  case class ByIndex(value: Int) extends PortReference {
    override def apply(assignments: Seq[PortAssignment]): PortAssignment =
      assignments(value)
    override def buildProto(builder: Protos.CheckDefinition.Builder): Unit =
      builder.setPortIndex(value)
  }

  def apply(value: Int): PortReference = ByIndex(value)

  def fromProto(pb: Protos.CheckDefinition): Option[PortReference] =
    if (pb.hasPortIndex) Some(ByIndex(pb.getPortIndex))
    else if (!pb.hasPort) Some(ByIndex(0))
    else None
}

sealed trait CheckWithPort extends Check {
  def portIndex: Option[PortReference]
  def port: Option[Int]
}

object CheckWithPort {
  val DefaultPortIndex = None
  val DefaultPort = None

  import mesosphere.marathon.api.v2.Validation.isTrue

  implicit val Validator: Validator[CheckWithPort] =
    isTrue("Check must specify either a port or a portIndex") { hc =>
      hc.portIndex.isDefined ^ hc.port.isDefined
    }
}

sealed trait MesosCheck extends Check {
  def interval: FiniteDuration
  def timeout: FiniteDuration
  def delay: FiniteDuration

  override protected def protoBuilder: Protos.CheckDefinition.Builder =
    super.protoBuilder.setDelaySeconds(delay.toSeconds.toInt)

  def toMesos(portAssignments: Seq[PortAssignment]): Option[MesosProtos.CheckInfo]
}

sealed trait MesosCheckWithPorts extends CheckWithPort { this: Check =>
  def effectivePort(portAssignments: Seq[PortAssignment]): Option[Int] = {
    port.orElse {
      val portAssignment: Option[PortAssignment] = portIndex.map(index => index(portAssignments))
      // Mesos enters the container's network to probe the port, hence we prefer `containerPort`
      // to `hostPort` here (as opposed to MarathonCheck which is the opposite)
      portAssignment.flatMap(_.containerPort).orElse(portAssignment.flatMap(_.hostPort))
    }
  }
}

case class MesosCommandCheck(
    interval: FiniteDuration = Check.DefaultInterval,
    timeout: FiniteDuration = Check.DefaultTimeout,
    delay: FiniteDuration = Check.DefaultDelay,
    command: Executable
) extends Check
    with MesosCheck {
  override def toProto: Protos.CheckDefinition = {
    protoBuilder
      .setProtocol(Protos.CheckDefinition.Protocol.COMMAND)
      .setCommand(Executable.toProto(command))
      .build
  }

  override def toMesos(portAssignments: Seq[PortAssignment]): Option[MesosProtos.CheckInfo] = {
    Option(
      MesosProtos.CheckInfo.newBuilder
        .setType(MesosProtos.CheckInfo.Type.COMMAND)
        .setIntervalSeconds(this.interval.toSeconds.toDouble)
        .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
        .setDelaySeconds(this.delay.toUnit(SECONDS))
        .setCommand(MesosProtos.CheckInfo.Command.newBuilder().setCommand(Executable.toProto(this.command)))
        .build()
    )
  }
}

object MesosCommandCheck {
  def mergeFromProto(proto: Protos.CheckDefinition): MesosCommandCheck =
    MesosCommandCheck(
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      delay = proto.getDelaySeconds.seconds,
      command = Executable.mergeFromProto(proto.getCommand)
    )
}

case class MesosHttpCheck(
    interval: FiniteDuration = Check.DefaultInterval,
    timeout: FiniteDuration = Check.DefaultTimeout,
    portIndex: Option[PortReference] = CheckWithPort.DefaultPortIndex,
    port: Option[Int] = CheckWithPort.DefaultPort,
    path: Option[String] = MesosHttpCheck.DefaultPath,
    protocol: Protocol = MesosHttpCheck.DefaultProtocol,
    delay: FiniteDuration = Check.DefaultDelay
) extends Check
    with MesosCheck
    with MesosCheckWithPorts {
  require(protocol == Protocol.HTTP)

  override def toProto: Protos.CheckDefinition = {
    val builder = protoBuilder
      .setProtocol(protocol)

    path.foreach(builder.setPath)

    portIndex.foreach(_.buildProto(builder))
    port.foreach(builder.setPort)

    builder.build
  }

  override def toMesos(portAssignments: Seq[PortAssignment]): Option[MesosProtos.CheckInfo] = {
    val port = effectivePort(portAssignments)
    port.map { checkPort =>
      val httpInfoBuilder = MesosProtos.CheckInfo.Http
        .newBuilder()
        .setPort(checkPort)

      path.foreach(httpInfoBuilder.setPath)

      MesosProtos.CheckInfo.newBuilder
        .setType(MesosProtos.CheckInfo.Type.HTTP)
        .setIntervalSeconds(this.interval.toSeconds.toDouble)
        .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
        .setDelaySeconds(this.delay.toUnit(SECONDS))
        .setHttp(httpInfoBuilder)
        .build()
    }
  }
}

object MesosHttpCheck {
  val DefaultPath = None
  val DefaultProtocol = Protocol.HTTP

  def mergeFromProto(proto: Protos.CheckDefinition): MesosHttpCheck =
    MesosHttpCheck(
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      delay = proto.getDelaySeconds.seconds,
      path = if (proto.hasPath) Some(proto.getPath) else DefaultPath,
      portIndex = PortReference.fromProto(proto),
      port = if (proto.hasPort) Some(proto.getPort) else CheckWithPort.DefaultPort,
      protocol = if (proto.hasProtocol) proto.getProtocol else DefaultProtocol
    )
}

case class MesosTcpCheck(
    interval: FiniteDuration = Check.DefaultInterval,
    timeout: FiniteDuration = Check.DefaultTimeout,
    portIndex: Option[PortReference] = CheckWithPort.DefaultPortIndex,
    port: Option[Int] = CheckWithPort.DefaultPort,
    delay: FiniteDuration = Check.DefaultDelay
) extends Check
    with MesosCheck
    with MesosCheckWithPorts {

  override def toProto: Protos.CheckDefinition = {
    val builder = protoBuilder
      .setProtocol(Protos.CheckDefinition.Protocol.TCP)

    portIndex.foreach(_.buildProto(builder))
    port.foreach(builder.setPort)

    builder.build
  }

  override def toMesos(portAssignments: Seq[PortAssignment]): Option[MesosProtos.CheckInfo] = {
    val port = effectivePort(portAssignments)
    port.map { checkPort =>
      val tcpInfoBuilder = MesosProtos.CheckInfo.Tcp.newBuilder().setPort(checkPort)

      MesosProtos.CheckInfo.newBuilder
        .setType(MesosProtos.CheckInfo.Type.TCP)
        .setIntervalSeconds(this.interval.toSeconds.toDouble)
        .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
        .setDelaySeconds(this.delay.toUnit(SECONDS))
        .setTcp(tcpInfoBuilder)
        .build()
    }
  }
}

object MesosTcpCheck {

  def mergeFromProto(proto: Protos.CheckDefinition): MesosTcpCheck =
    MesosTcpCheck(
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      delay = proto.getDelaySeconds.seconds,
      portIndex = PortReference.fromProto(proto),
      port = if (proto.hasPort) Some(proto.getPort) else None
    )
}

object Check {
  val DefaultProtocol = Protocol.HTTP
  val DefaultInterval = 1.minute
  val DefaultTimeout = 20.seconds
  val DefaultDelay = 15.seconds

  implicit val Validator: Validator[Check] = new Validator[Check] {
    override def apply(hc: Check): Result = {
      hc match {
        case h: CheckWithPort => CheckWithPort.Validator(h)
        case _ => Success
      }
    }
  }

  def fromProto(proto: Protos.CheckDefinition): Check = {
    proto.getProtocol match {
      case Protocol.COMMAND => MesosCommandCheck.mergeFromProto(proto)
      case Protocol.TCP => MesosTcpCheck.mergeFromProto(proto)
      case Protocol.HTTP => MesosHttpCheck.mergeFromProto(proto)
    }
  }
}
