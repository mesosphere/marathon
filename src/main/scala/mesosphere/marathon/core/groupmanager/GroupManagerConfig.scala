package mesosphere.marathon.core.groupmanager

import org.rogach.scallop.ScallopConf

trait GroupManagerConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val groupManagerRequestTimeout = opt[Int](
    "group_manager_request_timeout",
    descr = "Foo",
    hidden = true,
    default = Some(1000))
}
