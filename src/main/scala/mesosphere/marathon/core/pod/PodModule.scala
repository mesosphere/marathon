package mesosphere.marathon.core.pod

import akka.stream.Materializer
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.storage.repository.ReadOnlyPodRepository

case class PodModule(groupManager: GroupManager,
                     podRepository: ReadOnlyPodRepository)
                    (implicit mat: Materializer){
  val podManager: PodManager = PodManagerImpl(groupManager, podRepository)
}
