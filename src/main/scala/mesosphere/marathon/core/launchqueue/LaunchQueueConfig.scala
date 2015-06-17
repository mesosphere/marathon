package mesosphere.marathon.core.launchqueue

import org.rogach.scallop.ScallopConf

trait LaunchQueueConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val launchQueueRequestTimeout = opt[Int]("launch_queue_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the launch queue actor.",
    hidden = true,
    default = Some(1000))

  lazy val taskLaunchNotificationTimeout = opt[Int]("task_launch_notification_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for matched tasks to be launched or rejected.",
    hidden = true,
    default = Some(3000))
}
