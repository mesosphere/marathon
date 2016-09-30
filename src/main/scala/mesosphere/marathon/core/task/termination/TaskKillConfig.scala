package mesosphere.marathon.core.task.termination

import org.rogach.scallop.ScallopConf
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

trait TaskKillConfig extends ScallopConf {

  private[this] lazy val _killChunkSize = opt[Int](
    "kill_chunk_size",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The maximum number of concurrently processed kills",
    noshort = true,
    hidden = true,
    default = Some(100) //scalastyle:off magic.number
  )

  private[this] lazy val _killRetryTimeout = opt[Long](
    "kill_retry_timeout",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The timeout after which unconfirmed task kills will be retried.",
    noshort = true,
    hidden = true,
    default = Some(10.seconds.toMillis)
  )

  lazy val killChunkSize: Int = _killChunkSize()
  lazy val killRetryTimeout: FiniteDuration = _killRetryTimeout().millis
}
