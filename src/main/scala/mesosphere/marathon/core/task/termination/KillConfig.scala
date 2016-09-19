package mesosphere.marathon.core.task.termination

import org.rogach.scallop.ScallopConf
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

trait KillConfig extends ScallopConf {

  private[this] lazy val _killChunkSize = opt[Int](
    "kill_chunk_size",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The maximum number of concurrently processed kills",
    noshort = true,
    hidden = true,
    default = Some(100)
  )

  private[this] lazy val _killRetryTimeout = opt[Long](
    "kill_retry_timeout",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The timeout after which an instance kill will be retried.",
    noshort = true,
    hidden = true,
    default = Some(10.seconds.toMillis)
  )

  private[this] lazy val _killRetryMax = opt[Int](
    "kill_retry_max",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The maximum number of kill retries before which an instance will be forcibly expunged from state.",
    noshort = true,
    hidden = true,
    default = Some(5)
  )

  lazy val killChunkSize: Int = _killChunkSize()
  lazy val killRetryTimeout: FiniteDuration = _killRetryTimeout().millis
  lazy val killRetryMax: Int = _killRetryMax()
}
