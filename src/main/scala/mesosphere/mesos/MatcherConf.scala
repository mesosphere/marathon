package mesosphere.mesos

import org.rogach.scallop.{ ScallopConf, ScallopOption }

import scala.concurrent.duration._

trait MatcherConf extends ScallopConf {

  def availableFeatures: Set[String]

  def drainingSeconds: ScallopOption[Long]

  def drainingTime: FiniteDuration = FiniteDuration(drainingSeconds(), SECONDS)
}
