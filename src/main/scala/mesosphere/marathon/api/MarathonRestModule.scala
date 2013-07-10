package mesosphere.marathon.api

import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.v1.{DebugResource, ServicesResource, ServiceResource}
import com.google.inject.{Guice, Scopes}

/**
 * @author Tobi Knaup
 */

class MarathonRestModule extends RestModule {

  protected override def configureServlets() {
    super.configureServlets()

    bind(classOf[ServiceResource]).in(Scopes.SINGLETON)
    bind(classOf[ServicesResource]).in(Scopes.SINGLETON)
    bind(classOf[DebugResource]).in(Scopes.SINGLETON)
    bind(classOf[RedirectFilter]).asEagerSingleton()

    //This filter will redirect to the master if running in HA mode.
    filter("/*").through(classOf[RedirectFilter])
  }
}
