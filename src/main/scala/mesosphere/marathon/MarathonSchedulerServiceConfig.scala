package mesosphere.marathon

import org.rogach.scallop.ScallopConf

trait MarathonSchedulerServiceConfig extends ScallopConf {

  lazy val maxActorStartupTime = opt[Long](
    "max_actor_startup_time",
    descr = "Maximum time to wait for starting up actors when gaining leadership.",
    hidden = true,
    default = Some(10000))

  lazy val schedulerActionsExecutionContextSize = opt[Int](
    "scheduler_actions_execution_context_size",
    default = Some(8),
    hidden = true,
    descr = "INTERNAL TUNING PARAMETER: Scheduler actions component's execution context thread pool size"
  )

}
