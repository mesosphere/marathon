package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.core.readiness.ReadinessCheck

import scala.concurrent.duration._

private[state] object ReadinessCheckSerializer {
  import scala.collection.JavaConverters._
  def fromProto(proto: Protos.ReadinessCheckDefinition): ReadinessCheck = {
    def opt[T](
      hasValue: Protos.ReadinessCheckDefinition => Boolean,
      getValue: Protos.ReadinessCheckDefinition => T): Option[T] = {
      if (hasValue(proto)) Some(getValue(proto))
      else None
    }

    ReadinessCheck(
      name = opt(_.hasName, _.getName).getOrElse(ReadinessCheck.DefaultName),
      protocol =
        opt(_.hasProtocol, _.getProtocol).map(ProtocolSerializer.fromProto).getOrElse(ReadinessCheck.DefaultProtocol),
      path = opt(_.hasPath, _.getPath).getOrElse(ReadinessCheck.DefaultPath),
      portName = opt(_.hasPortName, _.getPortName).getOrElse(ReadinessCheck.DefaultPortName),
      interval = opt(_.hasIntervalMillis, _.getIntervalMillis.millis).getOrElse(ReadinessCheck.DefaultInterval),
      timeout = opt(_.hasTimeoutMillis, _.getTimeoutMillis.millis).getOrElse(ReadinessCheck.DefaultTimeout),
      httpStatusCodesForReady =
        opt(
          _.getHttpStatusCodeForReadyCount > 0,
          _.getHttpStatusCodeForReadyList.iterator().asScala.map(_.intValue()).toSet
        ).getOrElse(ReadinessCheck.DefaultHttpStatusCodesForReady),
      preserveLastResponse =
        opt(_.hasPreserveLastResponse, _.getPreserveLastResponse).getOrElse(ReadinessCheck.DefaultPreserveLastResponse)
    )
  }

  def toProto(check: ReadinessCheck): Protos.ReadinessCheckDefinition = {
    Protos.ReadinessCheckDefinition.newBuilder()
      .setName(check.name)
      .setProtocol(ProtocolSerializer.toProto(check.protocol))
      .setPath(check.path)
      .setPortName(check.portName)
      .setIntervalMillis(check.interval.toMillis)
      .setTimeoutMillis(check.timeout.toMillis)
      .addAllHttpStatusCodeForReady(check.httpStatusCodesForReady.map(java.lang.Integer.valueOf(_)).asJava)
      .setPreserveLastResponse(check.preserveLastResponse)
      .build()
  }

  object ProtocolSerializer {
    def fromProto(proto: Protos.ReadinessCheckDefinition.Protocol): ReadinessCheck.Protocol = {
      proto match {
        case Protos.ReadinessCheckDefinition.Protocol.HTTP  => ReadinessCheck.Protocol.HTTP
        case Protos.ReadinessCheckDefinition.Protocol.HTTPS => ReadinessCheck.Protocol.HTTPS
      }
    }

    def toProto(protocol: ReadinessCheck.Protocol): Protos.ReadinessCheckDefinition.Protocol = {
      protocol match {
        case ReadinessCheck.Protocol.HTTP  => Protos.ReadinessCheckDefinition.Protocol.HTTP
        case ReadinessCheck.Protocol.HTTPS => Protos.ReadinessCheckDefinition.Protocol.HTTPS
      }
    }
  }
}
