package mesosphere.marathon.core.history

import org.rogach.scallop.ScallopConf

trait HistoryConf extends ScallopConf {
  //scalastyle:off magic.number
  lazy val eventStreamMaxOutstandingMessages = opt[Int](
    "event_stream_max_outstanding_messages",
    descr = "The event stream buffers events, that are not already consumed by clients. " +
      "This number defines the number of events that get buffered on the server side, before messages are dropped.",
    noshort = true,
    default = Some(50)
  )
  //scalastyle:on magic.number
}
