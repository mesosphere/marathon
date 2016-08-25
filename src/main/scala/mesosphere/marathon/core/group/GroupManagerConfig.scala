package mesosphere.marathon.core.group

import org.rogach.scallop.ScallopConf

import scala.concurrent.duration._

trait GroupManagerConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val groupManagerRequestTimeout = opt[Int](
    "group_manager_request_timeout",
    descr = "INTERNAL TUNING PARAMETER: Timeout (in ms) for requests to the group manager actor.",
    hidden = true,
    default = Some(10.seconds.toMillis.toInt))
}
