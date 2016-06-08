package mesosphere.marathon

import com.google.inject.Inject
import mesosphere.marathon.core.election.ElectionService

/**
  * Trigger abdicating leadership after disconnection from Mesos.
  */
class SchedulerCallbacksServiceAdapter @Inject() (
    electionService: ElectionService) extends SchedulerCallbacks {
  override def disconnected(): Unit = {
    // Abdicate leadership when we become disconnected from the Mesos master.
    electionService.abdicateLeadership()
  }
}
