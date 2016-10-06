package mesosphere.marathon.core.appinfo

import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

trait PodStatusService {

  /**
    * @return the status of the pod at the given path, if such a pod exists
    */
  def selectPodStatus(id: PathId, selector: PodSelector = Selector.all): Future[Option[PodStatus]]
}
