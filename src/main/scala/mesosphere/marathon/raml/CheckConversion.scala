package mesosphere.marathon
package raml

import mesosphere.marathon.Protos.CheckDefinition
import mesosphere.marathon.core.check._
import mesosphere.marathon.core.check.PortReference
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.duration._

trait CheckConversion {

  implicit val httpCheckSchemeRamlReader: Reads[HttpScheme, CheckDefinition.Protocol] = Reads {
    case HttpScheme.Http => CheckDefinition.Protocol.HTTP
  }

  implicit val httpCheckSchemeRamlWriter: Writes[CheckDefinition.Protocol, HttpScheme] = Writes {
    case CheckDefinition.Protocol.HTTP => HttpScheme.Http
    case p => throw new IllegalArgumentException(s"cannot convert check protocol $p to raml")
  }


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

  implicit val appCheckRamlReader: Reads[AppCheck, MesosCheck] = Reads {
    case AppCheck(Some(httpCheck), None, None, interval, timeout, delay) =>
      //      val portIndex = httpCheck.portIndex.collect{ case index: PortReference.ByIndex => index.value }
      MesosHttpCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        path = httpCheck.path,
        protocol = Raml.fromRaml[HttpScheme, CheckDefinition.Protocol](httpCheck.scheme),
        //        portIndex = Some(PortReference(httpCheck.portIndex)),
        delay = delay.seconds,
      )
    case AppCheck(None, Some(tcpCheck),  None, interval, timeout, delay) =>
      MesosTcpCheck(
        interval = interval.seconds,
        timeout = timeout.seconds,
        delay = delay.seconds,
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

  implicit val checkStatusRamlWriter: Writes[MesosProtos.CheckStatusInfo, CheckStatus]= Writes { checkStatus =>
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

  implicit val checkRamlWriter: Writes[MesosCheck, Check] = Writes { check =>
    def requiredField[T](msg: String): T = throw new IllegalStateException(msg)
    def requireEndpoint(portIndex: Option[PortReference]): String = portIndex.fold(
      requiredField[String]("portIndex undefined for check")
    ) {
//      case byName: PortReference.ByName => byName.value
      case index => throw new IllegalStateException(s"unsupported type of port-index for $index")
    }

    val partialCheck = Check(
      intervalSeconds = check.interval.toSeconds.toInt,
      timeoutSeconds = check.timeout.toSeconds.toInt,
      delaySeconds = check.delay.toSeconds.toInt
    )
    check match {
      case httpCheck: MesosHttpCheck =>
        partialCheck.copy(
          http = Some(HttpCheck(
            endpoint = requireEndpoint(httpCheck.portIndex),
            path = httpCheck.path,
            scheme = Raml.toRaml(httpCheck.protocol)))
        )
      case tcpCheck: MesosTcpCheck =>
        partialCheck.copy(
          tcp = Some(TcpCheck(
            endpoint = requireEndpoint(tcpCheck.portIndex)
          ))
        )
      case cmdCheck: MesosCommandCheck =>
        partialCheck.copy(
          exec = Some(CommandCheck(
            command = cmdCheck.command match {
              case cmd: state.Command => ShellCommand(cmd.value)
              case argv: state.ArgvList => ArgvCommand(argv.value)
            }
          ))
        )
    }
  }
}
