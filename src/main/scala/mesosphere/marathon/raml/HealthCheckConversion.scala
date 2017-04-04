package mesosphere.marathon
package raml

import mesosphere.marathon.Protos.HealthCheckDefinition
import mesosphere.marathon.core.health._
import mesosphere.marathon.state.{ ArgvList, Command, Executable }

import scala.concurrent.duration._

trait HealthCheckConversion {

  implicit val httpSchemeRamlReader: Reads[HttpScheme, HealthCheckDefinition.Protocol] = Reads {
    case HttpScheme.Http => HealthCheckDefinition.Protocol.MESOS_HTTP
    case HttpScheme.Https => HealthCheckDefinition.Protocol.MESOS_HTTPS
  }

  implicit val httpSchemeRamlWriter: Writes[HealthCheckDefinition.Protocol, HttpScheme] = Writes {
    case HealthCheckDefinition.Protocol.MESOS_HTTP => HttpScheme.Http
    case HealthCheckDefinition.Protocol.MESOS_HTTPS => HttpScheme.Https
    case p => throw new IllegalArgumentException(s"cannot convert health-check protocol $p to raml")
  }

  implicit val commandHealthCheckRamlReader: Reads[CommandHealthCheck, Executable] = Reads { commandHealthCheck =>
    commandHealthCheck.command match {
      case sc: ShellCommand => Command(sc.shell)
      case av: ArgvCommand => ArgvList(av.argv)
    }
  }

  implicit val healthCheckRamlReader: Reads[HealthCheck, MesosHealthCheck] = Reads {
    case HealthCheck(Some(httpCheck), None, None, gracePeriod, interval, maxConFailures, timeout, delay) =>
      MesosHttpHealthCheck(
        gracePeriod = gracePeriod.seconds,
        interval = interval.seconds,
        timeout = timeout.seconds,
        maxConsecutiveFailures = maxConFailures,
        delay = delay.seconds,
        path = httpCheck.path,
        protocol = Raml.fromRaml[HttpScheme, HealthCheckDefinition.Protocol](httpCheck.scheme),
        portIndex = Some(PortReference(httpCheck.endpoint))
      )
    case HealthCheck(None, Some(tcpCheck), None, gracePeriod, interval, maxConFailures, timeout, delay) =>
      MesosTcpHealthCheck(
        gracePeriod = gracePeriod.seconds,
        interval = interval.seconds,
        timeout = timeout.seconds,
        maxConsecutiveFailures = maxConFailures,
        delay = delay.seconds,
        portIndex = Some(PortReference(tcpCheck.endpoint))
      )
    case HealthCheck(None, None, Some(execCheck), gracePeriod, interval, maxConFailures, timeout, delay) =>
      MesosCommandHealthCheck(
        gracePeriod = gracePeriod.seconds,
        interval = interval.seconds,
        timeout = timeout.seconds,
        maxConsecutiveFailures = maxConFailures,
        delay = delay.seconds,
        command = Raml.fromRaml(execCheck)
      )
    case _ =>
      throw new IllegalStateException("illegal RAML HealthCheck: expected one of http, tcp or exec checks")
  }

  implicit val healthCheckRamlWriter: Writes[MesosHealthCheck, HealthCheck] = Writes { check =>
    def requiredField[T](msg: String): T = throw new IllegalStateException(msg)
    def requireEndpoint(portIndex: Option[PortReference]): String = portIndex.fold(
      requiredField[String]("portIndex undefined for health-check")
    ) {
        case byName: PortReference.ByName => byName.value
        case index => throw new IllegalStateException(s"unsupported type of port-index for $index")
      }

    val partialCheck = HealthCheck(
      gracePeriodSeconds = check.gracePeriod.toSeconds.toInt,
      intervalSeconds = check.interval.toSeconds.toInt,
      maxConsecutiveFailures = check.maxConsecutiveFailures,
      timeoutSeconds = check.timeout.toSeconds.toInt,
      delaySeconds = check.delay.toSeconds.toInt
    )
    check match {
      case httpCheck: MesosHttpHealthCheck =>
        partialCheck.copy(
          http = Some(HttpHealthCheck(
            endpoint = requireEndpoint(httpCheck.portIndex),
            path = httpCheck.path,
            scheme = Raml.toRaml(httpCheck.protocol)))
        )
      case tcpCheck: MesosTcpHealthCheck =>
        partialCheck.copy(
          tcp = Some(TcpHealthCheck(
            endpoint = requireEndpoint(tcpCheck.portIndex)
          ))
        )
      case cmdCheck: MesosCommandHealthCheck =>
        partialCheck.copy(
          exec = Some(CommandHealthCheck(
            command = cmdCheck.command match {
              case cmd: state.Command => ShellCommand(cmd.value)
              case argv: state.ArgvList => ArgvCommand(argv.value)
            }
          ))
        )
    }
  }

  implicit val appHealthCheckWrites: Writes[core.health.HealthCheck, AppHealthCheck] = Writes { health =>

    implicit val commandCheckWrites: Writes[state.Executable, CommandCheck] = Writes {
      case state.Command(value) => CommandCheck(value)
      case state.ArgvList(args) => throw SerializationFailedException("serialization of ArgvList not supported")
    }

    import Protos.HealthCheckDefinition.{ Protocol => ProtocolProto }
    implicit val protocolWrites: Writes[ProtocolProto, AppHealthCheckProtocol] = Writes {
      case ProtocolProto.COMMAND => AppHealthCheckProtocol.Command
      case ProtocolProto.HTTP => AppHealthCheckProtocol.Http
      case ProtocolProto.HTTPS => AppHealthCheckProtocol.Https
      case ProtocolProto.TCP => AppHealthCheckProtocol.Tcp
      case ProtocolProto.MESOS_HTTP => AppHealthCheckProtocol.MesosHttp
      case ProtocolProto.MESOS_HTTPS => AppHealthCheckProtocol.MesosHttps
      case ProtocolProto.MESOS_TCP => AppHealthCheckProtocol.MesosTcp
    }

    def create(
      protocol: AppHealthCheckProtocol,
      command: Option[CommandCheck] = None,
      ignoreHttp1xx: Option[Boolean] = None,
      path: Option[String] = None,
      port: Option[Int] = None,
      portReference: Option[PortReference] = None,
      delay: Duration = core.health.HealthCheck.DefaultDelay): AppHealthCheck = {
      val portIndex = portReference.collect{ case index: PortReference.ByIndex => index.value }
      AppHealthCheck(
        gracePeriodSeconds = health.gracePeriod.toSeconds.toInt,
        command = command,
        ignoreHttp1xx = ignoreHttp1xx,
        intervalSeconds = health.interval.toSeconds.toInt,
        maxConsecutiveFailures = health.maxConsecutiveFailures,
        path = path,
        port = port,
        portIndex = portIndex,
        protocol = protocol,
        timeoutSeconds = health.timeout.toSeconds.toInt,
        delaySeconds = delay.toSeconds.toInt
      )
    }

    import raml.{ AppHealthCheckProtocol => AHCP }
    health match {
      case hc: MarathonHttpHealthCheck => create(hc.protocol.toRaml[AppHealthCheckProtocol], ignoreHttp1xx = Some(hc.ignoreHttp1xx), path = hc.path, port = hc.port, portReference = hc.portIndex)
      case hc: MarathonTcpHealthCheck => create(AHCP.Tcp, port = hc.port, portReference = hc.portIndex)
      case hc: MesosCommandHealthCheck => create(AHCP.Command, command = Some(hc.command.toRaml), delay = hc.delay)
      case hc: MesosHttpHealthCheck => create(hc.protocol.toRaml[AppHealthCheckProtocol], path = hc.path, port = hc.port, portReference = hc.portIndex, delay = hc.delay)
      case hc: MesosTcpHealthCheck => create(AHCP.MesosTcp, port = hc.port, portReference = hc.portIndex, delay = hc.delay)
    }
  }

  import Protos.ResidencyDefinition.{ TaskLostBehavior => TaskLostProto }
  implicit val taskLostBehaviorWrites: Writes[TaskLostProto, TaskLostBehavior] = Writes {
    case TaskLostProto.WAIT_FOREVER => TaskLostBehavior.WaitForever
    case TaskLostProto.RELAUNCH_AFTER_TIMEOUT => TaskLostBehavior.RelaunchAfterTimeout
  }
}

object HealthCheckConversion extends HealthCheckConversion
