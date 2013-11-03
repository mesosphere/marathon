package mesosphere.marathon.api

import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.v1.{TasksResource, EndpointsResource, DebugResource, AppsResource}
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
    bind(classOf[TasksResource]).in(Scopes.SINGLETON)
    bind(classOf[LeaderProxyFilter]).asEagerSingleton()

    //This filter will redirect to the master if running in HA mode.
    filter("/*").through(classOf[LeaderProxyFilter])
  }
}
