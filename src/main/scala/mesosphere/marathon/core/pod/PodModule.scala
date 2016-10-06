package mesosphere.marathon.core.pod

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.storage.repository.ReadOnlyPodRepository

import scala.concurrent.ExecutionContext

case class PodModule(
    groupManager: GroupManager,
    podRepository: ReadOnlyPodRepository)(implicit ctx: ExecutionContext) {

  lazy val podManager: PodManager = new PodManagerImpl(groupManager, podRepository)
}
