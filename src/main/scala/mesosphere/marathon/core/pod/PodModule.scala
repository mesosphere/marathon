package mesosphere.marathon
package core.pod

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl

case class PodModule(groupManager: GroupManager) {
  lazy val podManager: PodManager = PodManagerImpl(groupManager)
}
