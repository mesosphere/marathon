package mesosphere.marathon.core.pod

import java.time.Clock

import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl

import scala.concurrent.ExecutionContext

case class PodModule(
  groupManager: GroupManager,
  podStatusService: PodStatusService)(implicit
  ctx: ExecutionContext,
  clock: Clock) {

  val podManager: PodManager = new PodManagerImpl(groupManager, podStatusService)
}
