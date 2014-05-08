package mesosphere.marathon.api

import mesosphere.chaos.http.RestModule
import mesosphere.jackson.CaseClassModule
import mesosphere.marathon.api.v2.json.MarathonModule
import com.google.inject.Scopes
import com.fasterxml.jackson.module.scala.DefaultScalaModule

/**
  * @author Tobi Knaup
  */

class MarathonRestModule extends RestModule {

  override val jacksonModules = Seq(
    new DefaultScalaModule with CaseClassModule,
    new MarathonModule
  )

  protected override def configureServlets() {
    super.configureServlets()

    // V1 API
    bind(classOf[v1.AppsResource]).in(Scopes.SINGLETON)
    bind(classOf[v1.DebugResource]).in(Scopes.SINGLETON)
    bind(classOf[v1.EndpointsResource]).in(Scopes.SINGLETON)
    bind(classOf[v1.TasksResource]).in(Scopes.SINGLETON)
    bind(classOf[v1.MarathonExceptionMapper]).asEagerSingleton()

    // V2 API
    bind(classOf[v2.AppsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.TasksResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.EventSubscriptionsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.QueueResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.GroupsResource]).in(Scopes.SINGLETON)

    // This filter will redirect to the master if running in HA mode.
    bind(classOf[LeaderProxyFilter]).asEagerSingleton()
    filter("/*").through(classOf[LeaderProxyFilter])
  }

}
