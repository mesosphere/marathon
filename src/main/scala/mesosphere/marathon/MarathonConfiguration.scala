package mesosphere.marathon

import org.rogach.scallop.ScallopConf

/**
 * @author Tobi Knaup
 */

trait MarathonConfiguration extends ScallopConf {

  lazy val mesosMaster = opt[String]("master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

}