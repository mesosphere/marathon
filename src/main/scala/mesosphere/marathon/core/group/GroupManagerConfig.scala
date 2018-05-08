package mesosphere.marathon
package core.group

import org.rogach.scallop.{ScallopConf, ScallopOption}

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

  lazy val groupManagerExecutionContextSize = opt[Int](
    "group_manager_execution_context_size",
    default = Some(Runtime.getRuntime().availableProcessors()),
    hidden = true,
    descr = "INTERNAL TUNING PARAMETER: Group manager module's execution context thread pool size"
  )

  lazy val maxRunningDeployments = opt[Int](
    "max_running_deployments",
    descr = "Max number of concurrently running deployments. Over the limit deployments will be dismissed.",
    noshort = true,
    default = Some(100)
  )

  def availableFeatures: Set[String]
  def localPortMin: ScallopOption[Int]
  def localPortMax: ScallopOption[Int]
  def zkTimeoutDuration: Duration
}
