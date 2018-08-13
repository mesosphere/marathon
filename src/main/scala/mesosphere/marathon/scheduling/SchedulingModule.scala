package mesosphere.marathon
package scheduling

import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor

class SchedulingModule(
    offerProcessor: OfferProcessor,
    instanceTracker: InstanceTracker,
    statusUpdateProcessor: TaskStatusUpdateProcessor,
    killService: KillService,
    launchQueue: LaunchQueue) {

  lazy val scheduler: Scheduler = LegacyScheduler(offerProcessor, instanceTracker, statusUpdateProcessor, killService, launchQueue)
}
