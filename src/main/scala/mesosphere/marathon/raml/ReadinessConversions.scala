package mesosphere.marathon
package raml

import mesosphere.marathon.core

import scala.collection.immutable.Seq

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
      httpStatusCodesForReady = check.httpStatusCodesForReady.to[Seq],
      preserveLastResponse = Some(check.preserveLastResponse)
    )
  }
}
