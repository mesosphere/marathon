package mesosphere.marathon
package raml

import mesosphere.marathon.Protos.CheckDefinition
import mesosphere.marathon.core.check.{Check => CoreCheck, _}
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.duration._

/**
  * Trait used to provide conversion from raml to Check or MesosCheck type (which is used to convert to protobuf)
  */
trait CheckConversion {

  implicit val commandCheckWrites: Writes[state.Executable, CommandCheck] = Writes {
    case state.Command(value) => CommandCheck(ShellCommand(value))
  }

  implicit val httpCheckSchemeRamlReader: Reads[HttpScheme, CheckDefinition.Protocol] = Reads {
    case HttpScheme.Http => CheckDefinition.Protocol.HTTP
  }

  implicit val httpCheckSchemeRamlWriter: Writes[CheckDefinition.Protocol, HttpScheme] = Writes {
    case CheckDefinition.Protocol.HTTP => HttpScheme.Http
    case p => throw new IllegalArgumentException(s"cannot convert check protocol $p to raml")
  }


  /*
  Used for pods checks
   */
  implicit val checkRamlReader: Reads[Check, MesosCheck] = Reads {
    case Check(Some(httpCheck), None, None, interval, timeout, delay) =>
//      val portIndex = httpCheck.portIndex.collect{ case index: PortReference.ByIndex => index.value }

      MesosHttpCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        path = httpCheck.path,
        protocol = Raml.fromRaml[HttpScheme, CheckDefinition.Protocol](httpCheck.scheme),
//        portIndex = Some(PortReference(httpCheck.portIndex)),
        delay = delay.seconds,
      )
    case Check(None, Some(tcpCheck),  None, interval, timeout, delay) =>
      MesosTcpCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        delay = delay.seconds,
//        portIndex = Some(PortReference(tcpCheck.portIndex))
      )
    case Check(None, None, Some(execCheck), interval, timeout, delay) =>
      MesosCommandCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        delay = delay.seconds,
        command = Raml.fromRaml(execCheck)
      )
    case _ =>
      throw new IllegalStateException("illegal RAML Check: expected one of http, tcp or exec checks")
  }

  /*
  Used for app checks
   */
  implicit val appCheckRamlReader: Reads[AppCheck, MesosCheck] = Reads {
    case AppCheck(Some(httpCheck), None, None, interval, timeout, delay) =>
      //      val portIndex = httpCheck.portIndex.collect{ case index: PortReference.ByIndex => index.value }
      MesosHttpCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        path = httpCheck.path,
        protocol = Raml.fromRaml[HttpScheme, CheckDefinition.Protocol](httpCheck.scheme),
        //        portIndex = Some(PortReference(httpCheck.portIndex)),
        port = httpCheck.port,
        delay = delay.seconds,
      )
    case AppCheck(None, Some(tcpCheck),  None, interval, timeout, delay) =>
      MesosTcpCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        delay = delay.seconds,
        port = tcpCheck.port,
        //        portIndex = Some(PortReference(tcpCheck.portIndex))
      )
    case AppCheck(None, None, Some(execCheck), interval, timeout, delay) =>
      MesosCommandCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        delay = delay.seconds,
        command = Raml.fromRaml(execCheck)
      )
    case _ =>
      throw new IllegalStateException("illegal RAML Check: expected one of http, tcp or exec checks")
  }

  /*
  Used for App CheckResult writing
   */
  implicit val checkStatusRamlWriter: Writes[MesosProtos.CheckStatusInfo, CheckStatus] = Writes { checkStatus =>
    CheckStatus.apply()
    val commandCheckStatus: Option[CommandCheckStatus] = if (checkStatus.hasCommand && checkStatus.getCommand.hasExitCode)
      Some(CommandCheckStatus(checkStatus.getCommand.getExitCode))
    else
      None

    val httpCheckStatus: Option[HttpCheckStatus] =  if (checkStatus.hasHttp && checkStatus.getHttp.hasStatusCode)
      Some(HttpCheckStatus(checkStatus.getHttp.getStatusCode))
    else
      None

    val tcpCheckStatus: Option[TCPCheckStatus] = if (checkStatus.hasTcp && checkStatus.getTcp.hasSucceeded)
      Some(TCPCheckStatus(checkStatus.getTcp.getSucceeded))
    else
      None

    CheckStatus(httpCheckStatus, tcpCheckStatus, commandCheckStatus)
  }

  implicit val checkRamlWriter: Writes[CoreCheck, AppCheck] = Writes {  check =>

    check match {
      case httpCheck: MesosHttpCheck =>
        AppCheck(
          intervalSeconds = check.interval.toSeconds.toInt,
          timeoutSeconds = check.timeout.toSeconds.toInt,
          delaySeconds = check.delay.toSeconds.toInt,
          http = Some(AppHttpCheck(
            path = httpCheck.path,
            scheme = Raml.toRaml(httpCheck.protocol),
            port = httpCheck.port,
            // TODO: fix portindex
            portIndex = None)),
          tcp = None,
          exec = None)
      case tcpCheck: MesosTcpCheck =>
        AppCheck(
          intervalSeconds = check.interval.toSeconds.toInt,
          timeoutSeconds = check.timeout.toSeconds.toInt,
          delaySeconds = check.delay.toSeconds.toInt,
          http = None,
          tcp = Some(AppTcpCheck(
            port = tcpCheck.port,
            // TODO: fix portindex
            portIndex = None)),
          exec = None)
      case cmdCheck: MesosCommandCheck =>
        AppCheck(
          intervalSeconds = check.interval.toSeconds.toInt,
          timeoutSeconds = check.timeout.toSeconds.toInt,
          delaySeconds = check.delay.toSeconds.toInt,
          http = None,
          tcp = None,
          exec = Some(Raml.toRaml(cmdCheck.command))
        )
    }
  }

  implicit val checkProtoRamlWriter: Writes[Protos.CheckDefinition, AppCheck] = Writes { check =>

    val prototype = AppCheck(
      intervalSeconds = if (check.hasIntervalSeconds) check.getIntervalSeconds else AppCheck.DefaultIntervalSeconds,
      timeoutSeconds = if (check.hasTimeoutSeconds) check.getTimeoutSeconds else AppCheck.DefaultTimeoutSeconds,
      delaySeconds = if (check.hasDelaySeconds) check.getDelaySeconds else AppCheck.DefaultDelaySeconds
    )

    check.getProtocol match {
      case CheckDefinition.Protocol.COMMAND => prototype.copy(
        exec = Some(CommandCheck(ShellCommand(check.getCommand.getValue)))
      )
      case CheckDefinition.Protocol.HTTP => prototype.copy(
        http = Some(AppHttpCheck(
          portIndex = if (check.hasPortIndex) Some(check.getPortIndex) else None,
          port = if (check.hasPort) Some(check.getPort) else None,
          path = if (check.hasPath) Some(check.getPath) else None))
      )
      case CheckDefinition.Protocol.TCP => prototype.copy(
        tcp = Some(AppTcpCheck(
          portIndex = if (check.hasPortIndex) Some(check.getPortIndex) else None,
          port = if (check.hasPort) Some(check.getPort) else None))
      )
    }
  }
}
