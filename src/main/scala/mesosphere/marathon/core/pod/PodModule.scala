package mesosphere.marathon.core.pod

import java.time.Clock

import mesosphere.marathon.DeploymentService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.concurrent.ExecutionContext

case class PodModule(
    groupManager: GroupManager,
    tracker: InstanceTracker,
    deploymentService: DeploymentService)(implicit
  ctx: ExecutionContext,
    clock: Clock) {

  val podManager: PodManager = new PodManagerImpl(groupManager, tracker, deploymentService)
}
