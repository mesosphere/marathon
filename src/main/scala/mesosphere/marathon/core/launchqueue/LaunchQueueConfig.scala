package mesosphere.marathon
package core.launchqueue

import org.rogach.scallop.ScallopConf

import scala.concurrent.duration._

trait LaunchQueueConfig extends ScallopConf {

  lazy val minimumViableTaskExecutionDurationMillis = opt[Long](
    "minimum_viable_task_execution_duration",
    descr = "Delay (in ms) after which a task is considered viable.",
    default = Some(60000))

  lazy val launchQueueRequestTimeout = opt[Int](
    "launch_queue_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the launch queue actor.",
    hidden = true,
    default = Some(1000))

  lazy val taskOpNotificationTimeout = opt[Int](
    "task_operation_notification_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for matched task opereations to be accepted or rejected.",
    hidden = true,
    default = Some(10000))

  lazy val minimumViableTaskExecutionDuration: FiniteDuration = minimumViableTaskExecutionDurationMillis().millis
}
