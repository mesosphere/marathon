package mesosphere.marathon

import com.google.inject.Inject

/**
  * Makes the [[MarathonSchedulerService]] service usable as [[SchedulerCallbacks]].
  */
class SchedulerCallbacksServiceAdapter @Inject() (
    schedulerService: MarathonSchedulerService) extends SchedulerCallbacks {
  override def disconnected(): Unit = {
    // Abdicate leadership when we become disconnected from the Mesos master.
    schedulerService.abdicateLeadership()
  }
}
