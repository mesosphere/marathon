package mesosphere.mesos

import org.rogach.scallop.ScallopOption
import mesosphere.marathon.MaintenanceBehavior

import scala.concurrent.duration._

trait MatcherConf {

  def availableFeatures: Set[String]

  def drainingSeconds: ScallopOption[Long]

  def drainingTime: FiniteDuration = FiniteDuration(drainingSeconds(), SECONDS)

  def gpuSchedulingBehavior: ScallopOption[String]

  def maintenanceBehavior: ScallopOption[MaintenanceBehavior]
}
