package mesosphere.marathon.core.pod

import mesosphere.marathon.api.v2.PodsResource

/**
  * Created by jdefelice on 8/31/16.
  */
class PodModule {

  val podSystem: PodsResource.System = new PodSubsystem()
}
