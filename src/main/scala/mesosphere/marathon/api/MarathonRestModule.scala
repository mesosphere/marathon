package mesosphere.marathon.api

import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.v1._
import com.google.inject.Scopes
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import mesosphere.marathon.api.v1.json.ConstraintModule

/**
 * @author Tobi Knaup
 */

class MarathonRestModule extends RestModule {

  override val jacksonModules = Seq(DefaultScalaModule, new ConstraintModule)


  protected override def configureServlets() {
    super.configureServlets()

    bind(classOf[AppsResource]).in(Scopes.SINGLETON)
    bind(classOf[DebugResource]).in(Scopes.SINGLETON)
    bind(classOf[EndpointsResource]).in(Scopes.SINGLETON)
    bind(classOf[TasksResource]).in(Scopes.SINGLETON)
    bind(classOf[LeaderProxyFilter]).asEagerSingleton()
    bind(classOf[MarathonExceptionMapper]).asEagerSingleton()

    //This filter will redirect to the master if running in HA mode.
    filter("/*").through(classOf[LeaderProxyFilter])
  }
}
