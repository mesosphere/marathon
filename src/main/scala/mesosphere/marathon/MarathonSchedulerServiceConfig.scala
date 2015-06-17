package mesosphere.marathon

import org.rogach.scallop.ScallopConf

trait MarathonSchedulerServiceConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val maxActorStartupTime = opt[Long]("max_actor_startup_time",
    descr = "Maximum time to wait for starting up actors when gaining leadership.",
    hidden = true,
    default = Some(10000))

}
