package mesosphere.marathon.api

import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.google.inject.Scopes
import mesosphere.chaos.http.RestModule
import mesosphere.jackson.CaseClassModule
import mesosphere.marathon.api.v2.json.MarathonModule
import mesosphere.marathon.event.http.HttpEventStreamServlet

class MarathonRestModule extends RestModule {

  override val jacksonModules = Seq(
    new DefaultScalaModule with CaseClassModule,
    new MarathonModule
  )

  protected override def configureServlets() {

    // Map some exceptions to HTTP responses
    bind(classOf[MarathonExceptionMapper]).asEagerSingleton()

    // V2 API
    bind(classOf[v2.AppsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.TasksResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.EventSubscriptionsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.QueueResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.GroupsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.InfoResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.LeaderResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.DeploymentsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.ArtifactsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.SchemaResource]).in(Scopes.SINGLETON)

    // This filter will redirect to the master if running in HA mode.
    bind(classOf[LeaderProxyFilter]).asEagerSingleton()
    filter("/*").through(classOf[LeaderProxyFilter])

    bind(classOf[CORSFilter]).asEagerSingleton()
    filter("/*").through(classOf[CORSFilter])

    bind(classOf[CacheDisablingFilter]).asEagerSingleton()
    filter("/*").through(classOf[CacheDisablingFilter])

    bind(classOf[HttpEventStreamServlet]).asEagerSingleton()
    serve("/v2/events").`with`(classOf[HttpEventStreamServlet])

    super.configureServlets()
  }
}
