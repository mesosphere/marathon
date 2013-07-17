package mesosphere.marathon.api

import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.v1.{EndpointsResource, DebugResource, AppsResource}
import com.google.inject.Scopes

/**
 * @author Tobi Knaup
 */

class MarathonRestModule extends RestModule {

  protected override def configureServlets() {
    super.configureServlets()

    bind(classOf[AppsResource]).in(Scopes.SINGLETON)
    bind(classOf[DebugResource]).in(Scopes.SINGLETON)
    bind(classOf[EndpointsResource]).in(Scopes.SINGLETON)
    bind(classOf[RedirectFilter]).asEagerSingleton()

    //This filter will redirect to the master if running in HA mode.
    filter("/*").through(classOf[RedirectFilter])
  }
}
