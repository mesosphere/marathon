package mesosphere.marathon.api

import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.v1.{ServicesResource, ServiceResource}
import com.google.inject.Scopes

/**
 * @author Tobi Knaup
 */

class MarathonRestModule extends RestModule {

  protected override def configureServlets() {
    super.configureServlets()

    bind(classOf[ServiceResource]).in(Scopes.SINGLETON)
    bind(classOf[ServicesResource]).in(Scopes.SINGLETON)
  }
}
