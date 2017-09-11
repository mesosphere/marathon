package mesosphere.marathon
package core.event

import org.rogach.scallop.{ ScallopConf, ScallopOption }

import scala.concurrent.duration.FiniteDuration

trait EventConf extends ScallopConf {
  lazy val eventStreamMaxOutstandingMessages: ScallopOption[Int] = opt[Int](
    "event_stream_max_outstanding_messages",
    descr = "The event stream buffers events, that are not already consumed by clients. " +
      "This number defines the number of events that get buffered on the server side, before messages are dropped.",
    noshort = true,
    default = Some(1024)
  )

  lazy val eventStreamLightweight: ScallopOption[Boolean] = opt[Boolean](
    "event_stream_lightweight",
    descr = "Reduces the amount of information broadcasted through the SSE event",
    default = Some(false),
    required = false,
    noshort = true,
    hidden = true
  )
  def zkTimeoutDuration: FiniteDuration
}
