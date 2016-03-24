package mesosphere.marathon.core.readiness

import com.wix.accord.dsl._
import org.apache.http.HttpStatus

import scala.concurrent.duration._

case class ReadinessCheck(
  name: String = ReadinessCheck.DefaultName,
  protocol: ReadinessCheck.Protocol = ReadinessCheck.DefaultProtocol,

  path: String = ReadinessCheck.DefaultPath,
  portName: String = ReadinessCheck.DefaultPortName,

  interval: FiniteDuration = ReadinessCheck.DefaultInterval,
  timeout: FiniteDuration = ReadinessCheck.DefaultTimeout,

  httpStatusCodesForReady: Set[Int] = ReadinessCheck.DefaultHttpStatusCodesForReady,
  preserveLastResponse: Boolean = ReadinessCheck.DefaultPreserveLastResponse)

object ReadinessCheck {

  val DefaultName = "readinessCheck"
  val DefaultProtocol = Protocol.HTTP
  val DefaultPortName = "httpApi"
  val DefaultPath = "/"
  val DefaultInterval = 10.seconds
  val DefaultTimeout = 10.seconds
  val DefaultHttpStatusCodesForReady = Set(HttpStatus.SC_OK)
  val DefaultPreserveLastResponse = false

  sealed trait Protocol
  object Protocol {
    case object HTTP extends Protocol
    case object HTTPS extends Protocol
  }

  implicit val readinessCheckValidator = validator[ReadinessCheck] { rc =>
  }
}
