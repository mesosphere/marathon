package mesosphere.marathon.core.pod

import java.time.Clock

import akka.stream.Materializer
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl

import scala.concurrent.ExecutionContext

case class PodModule(groupManager: GroupManager)(implicit mat: Materializer, ctx: ExecutionContext, clock: Clock) {
  val podManager: PodManager = PodManagerImpl(groupManager)
}
