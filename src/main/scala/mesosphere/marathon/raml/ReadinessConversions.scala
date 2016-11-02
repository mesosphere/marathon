package mesosphere.marathon
package raml

import mesosphere.marathon.stream.Implicits._
import scala.concurrent.duration._

trait ReadinessConversions {

  implicit val readinessProtocolWrites: Writes[core.readiness.ReadinessCheck.Protocol, HttpScheme] = Writes {
    case core.readiness.ReadinessCheck.Protocol.HTTP => HttpScheme.Http
    case core.readiness.ReadinessCheck.Protocol.HTTPS => HttpScheme.Https
  }

  implicit val readinessCheckWrites: Writes[core.readiness.ReadinessCheck, ReadinessCheck] = Writes { check =>
    ReadinessCheck(
      name = check.name,
      protocol = check.protocol.toRaml,
      path = check.path,
      portName = check.portName,
      intervalSeconds = check.interval.toSeconds.toInt,
      timeoutSeconds = check.timeout.toSeconds.toInt,
      httpStatusCodesForReady = Option(check.httpStatusCodesForReady),
      preserveLastResponse = check.preserveLastResponse
    )
  }

  implicit val readinessProtocolReads: Reads[HttpScheme, core.readiness.ReadinessCheck.Protocol] = Reads {
    case HttpScheme.Http => core.readiness.ReadinessCheck.Protocol.HTTP
    case HttpScheme.Https => core.readiness.ReadinessCheck.Protocol.HTTPS
  }

  implicit val appReadinessRamlReader: Reads[ReadinessCheck, core.readiness.ReadinessCheck] = Reads { check =>
    core.readiness.ReadinessCheck(
      name = check.name,
      protocol = check.protocol.fromRaml,
      path = check.path,
      portName = check.portName,
      interval = check.intervalSeconds.seconds,
      timeout = check.timeoutSeconds.seconds,
      httpStatusCodesForReady = check.httpStatusCodesForReady.getOrElse(
        // normalization should have taken care of this already..
        throw SerializationFailedException("httpStatusCodesForReady must be specified")),
      preserveLastResponse = check.preserveLastResponse
    )
  }

  implicit val appReadinessProtocolProtoRamlWriter: Writes[Protos.ReadinessCheckDefinition.Protocol, HttpScheme] = Writes { proto =>
    import Protos.ReadinessCheckDefinition.Protocol._
    proto match {
      case HTTP => HttpScheme.Http
      case HTTPS => HttpScheme.Https
      case badProtocol => throw new IllegalStateException(s"unsupported readiness check protocol $badProtocol")
    }
  }

  implicit val appReadinessProtoRamlWriter: Writes[Protos.ReadinessCheckDefinition, ReadinessCheck] = Writes { rc =>
    ReadinessCheck(
      name = if (rc.hasName) rc.getName else ReadinessCheck.DefaultName,
      protocol = if (rc.hasProtocol) rc.getProtocol.toRaml else ReadinessCheck.DefaultProtocol,
      path = if (rc.hasPath) rc.getPath else ReadinessCheck.DefaultPath,
      portName = if (rc.hasPortName) rc.getPortName else ReadinessCheck.DefaultPortName,
      intervalSeconds = if (rc.hasIntervalMillis) (rc.getIntervalMillis / 1000).toInt else ReadinessCheck.DefaultIntervalSeconds,
      timeoutSeconds = if (rc.hasTimeoutMillis) (rc.getTimeoutMillis / 1000).toInt else ReadinessCheck.DefaultTimeoutSeconds,
      httpStatusCodesForReady = if (rc.getHttpStatusCodeForReadyCount > 0) Option(rc.getHttpStatusCodeForReadyList.map(_.intValue())(collection.breakOut)) else None,
      preserveLastResponse = if (rc.hasPreserveLastResponse) rc.getPreserveLastResponse else ReadinessCheck.DefaultPreserveLastResponse
    )
  }
}
