package mesosphere.marathon
package core.launchqueue

import org.rogach.scallop.ScallopConf

trait LaunchQueueConfig extends ScallopConf {

  lazy val launchQueueRequestTimeout = opt[Int](
    "launch_queue_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the launch queue actor.",
    hidden = true,
    default = Some(3000))

  lazy val taskOpNotificationTimeout = opt[Int](
    "task_operation_notification_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for matched task operations to be accepted or rejected.",
    hidden = true,
    default = Some(30000))

}
