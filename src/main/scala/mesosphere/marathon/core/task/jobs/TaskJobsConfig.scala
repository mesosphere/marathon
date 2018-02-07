package mesosphere.marathon
package core.task.jobs

import org.rogach.scallop.ScallopConf
import scala.concurrent.duration._

trait TaskJobsConfig extends ScallopConf {

  private[this] lazy val taskLostExpungeInitialDelayValue = opt[Long](
    "task_lost_expunge_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
      "begins to periodically perform task expunge gc operations",
    default = Some(5.minutes.toMillis))

  private[this] lazy val taskLostExpungeIntervalValue = opt[Long](
    "task_lost_expunge_interval",
    descr = "This is the length of time in milliseconds, for lost task gc operations.",
    default = Some(30.seconds.toMillis))

  def taskLostExpungeInitialDelay: FiniteDuration = taskLostExpungeInitialDelayValue().millis
  def taskLostExpungeInterval: FiniteDuration = taskLostExpungeIntervalValue().millis
}
