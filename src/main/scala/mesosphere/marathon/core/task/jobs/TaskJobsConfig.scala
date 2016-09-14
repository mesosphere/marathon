package mesosphere.marathon.core.task.jobs

import org.rogach.scallop.ScallopConf
import scala.concurrent.duration._

trait TaskJobsConfig extends ScallopConf {
  //scalastyle:off magic.number

  private[this] lazy val taskLostExpungeGCValue = opt[Long](
    "task_lost_expunge_gc",
    descr = "This is the length of time in milliseconds, until a lost task is garbage collected and expunged " +
      "from the task tracker and task repository.",
    default = Some(75.seconds.toMillis))

  private[this] lazy val taskLostExpungeInitialDelayValue = opt[Long](
    "task_lost_expunge_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
      "begins to periodically perform task expunge gc operations",
    default = Some(5.minutes.toMillis))

  private[this] lazy val taskLostExpungeIntervalValue = opt[Long](
    "task_lost_expunge_interval",
    descr = "This is the length of time in milliseconds, for lost task gc operations.",
    default = Some(30.seconds.toMillis))

  def taskLostExpungeGC: FiniteDuration = taskLostExpungeGCValue().millis
  def taskLostExpungeInitialDelay: FiniteDuration = taskLostExpungeInitialDelayValue().millis
  def taskLostExpungeInterval: FiniteDuration = taskLostExpungeIntervalValue().millis
}
