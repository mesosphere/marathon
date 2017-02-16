package mesosphere.marathon
package core.group

import org.rogach.scallop.{ ScallopConf, ScallopOption }

import scala.concurrent.duration._

trait GroupManagerConfig extends ScallopConf {

  lazy val internalMaxQueuedRootGroupUpdates = opt[Int](
    "max_queued_root_group_updates",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The maximum number of root group updates that we queue before rejecting updates.",
    noshort = true,
    hidden = true,
    default = Some(500)
  )

  lazy val groupManagerRequestTimeout = opt[Int](
    "group_manager_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the group manager actor.",
    hidden = true,
    default = Some(10.seconds.toMillis.toInt))

  def availableFeatures: Set[String]
  def localPortMin: ScallopOption[Int]
  def localPortMax: ScallopOption[Int]
  def zkTimeoutDuration: Duration
}
