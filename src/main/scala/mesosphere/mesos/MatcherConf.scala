package mesosphere.mesos

import mesosphere.marathon.GpuSchedulingBehavior
import org.rogach.scallop.ScallopOption

import scala.concurrent.duration._

trait MatcherConf {

  def availableFeatures: Set[String]

  def drainingSeconds: ScallopOption[Long]

  def drainingTime: FiniteDuration = FiniteDuration(drainingSeconds(), SECONDS)

  def gpuSchedulingBehavior: ScallopOption[GpuSchedulingBehavior]

  def maintenanceMode: ScallopOption[Boolean]
}
