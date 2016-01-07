package mesosphere.marathon.core.task.update

import org.rogach.scallop.ScallopConf

trait TaskStatusUpdateConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val internalMaxParallelStatusUpdates = opt[Int]("max_parallel_status_updates",
    descr = "INTERNAL TUNING PARAMETER: The maximum number of status updates that get processed in parallel.",
    noshort = true,
    hidden = true,
    default = Some(20)
  )

  lazy val internalMaxQueuedStatusUpdates = opt[Int]("max_queued_status_updates",
    descr = "INTERNAL TUNING PARAMETER: The maximum number of status updates that we queue for processing." +
      " Mesos will resent status updates which we do not acknowledge.",
    noshort = true,
    hidden = true,
    default = Some(10000)
  )
}
