package mesosphere.marathon.core.pod

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl

case class PodModule(groupManager: GroupManager) {
  val podManager: PodManager = PodManagerImpl(groupManager)
}
