package mesosphere.marathon.core.autoscale

import org.rogach.scallop.{ ScallopConf, ScallopOption }
import scala.concurrent.duration._

trait AutoScaleConfig extends ScallopConf {

  //already defined in MarathonConf
  def scaleAppsInitialDelay: ScallopOption[Long]

  //already defined in MarathonConf
  def scaleAppsInterval: ScallopOption[Long]

  lazy val forceDeploymentTimeoutMillis = opt[Long] ("auto_scale_force_deployment_timeout",
    noshort = true,
    descr = "If an application is in a scaling deployment, and does not finish in time, a new deployment is started.",
    default = Some(1.hour.toMillis)
  )

  private[autoscale] lazy val initialDelay: FiniteDuration = scaleAppsInitialDelay().millis
  private[autoscale] lazy val interval: FiniteDuration = scaleAppsInterval().millis
  private[autoscale] lazy val forceDeploymentTimeout: FiniteDuration = forceDeploymentTimeoutMillis().millis
}
