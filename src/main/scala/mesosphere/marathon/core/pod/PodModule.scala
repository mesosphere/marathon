package mesosphere.marathon.core.pod

import akka.stream.Materializer
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl

import scala.concurrent.ExecutionContext

case class PodModule(groupManager: GroupManager)(implicit mat: Materializer, ctx: ExecutionContext) {
  val podManager: PodManager = PodManagerImpl(groupManager)
}
