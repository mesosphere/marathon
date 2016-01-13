package mesosphere.marathon.core.task.tracker

import org.rogach.scallop.ScallopConf

trait TaskTrackerConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val internalTaskTrackerRequestTimeout = opt[Int]("task_tracker_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the taskTracker.",
    hidden = true,
    default = Some(10000))

  lazy val internalTaskUpdateRequestTimeout = opt[Int]("task_update_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for task update requests.",
    hidden = true,
    default = Some(10000))
}
