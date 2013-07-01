package mesosphere.marathon

import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.v1.ServiceResource
import com.google.inject.Scopes

/**
 * @author Tobi Knaup
 */

class MarathonModule(config: MarathonConfiguration) extends RestModule {

  protected override def configureServlets() {
    super.configureServlets()

    bind(classOf[ServiceResource]).in(Scopes.SINGLETON)

    bind(classOf[MarathonSchedulerService]).toInstance(
      new MarathonSchedulerService(config)
    )
  }
}
