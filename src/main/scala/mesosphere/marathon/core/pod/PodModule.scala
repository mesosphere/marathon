package mesosphere.marathon.core.pod

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl

import scala.concurrent.ExecutionContext

case class PodModule(
    groupManager: GroupManager)(implicit ctx: ExecutionContext) {

  lazy val podManager: PodManager = new PodManagerImpl(groupManager)
}
