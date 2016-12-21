package mesosphere.marathon
package core.deployment

import org.rogach.scallop.ScallopConf

import scala.concurrent.duration._

/**
  * Config parameters for the upgrade module.
  */
trait DeploymentConfig extends ScallopConf {

  lazy val deploymentManagerRequestTimeout = opt[Int](
    "deployment_manager_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the deployment manager actor.",
    hidden = true,
    default = Some(1000))

  lazy val killsPerBatch = opt[Int](
    "killsPerBatch",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The number of kills that can be issued in one batch size.",
    noshort = true,
    hidden = true,
    default = Some(100)
  )

  /**
    * Defines the duration of one batch cycle
    */
  lazy val killBatchDuration = opt[Long](
    "killBatchDuration",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The duration of one batch cycle in milliseconds.",
    noshort = true,
    hidden = true,
    default = Some(10000L) //10 seconds
  )

  def killBatchCycle: FiniteDuration = killBatchDuration().millis
  def killBatchSize: Int = killsPerBatch()
  def deploymentManagerRequestDuration = deploymentManagerRequestTimeout().milliseconds

}
