package mesosphere.marathon
package api

import com.google.inject.AbstractModule
import javax.inject.Named

import com.google.inject.{ Provides, Scopes, Singleton }
import com.google.common.util.concurrent.{ AbstractIdleService, Service }
import mesosphere.chaos.ServiceStatus
import mesosphere.chaos.http._
import mesosphere.marathon.io.SSLContextUtil

/**
  * Setup the dependencies for the LeaderProxyFilter.
  * This filter will redirect to the master if running in HA mode.
  */
class LeaderProxyFilterModule extends AbstractModule {
  override def configure(): Unit = {
  }

  @Provides
  @Singleton
  def provideRequestForwarder(
    httpConf: HttpConf,
    leaderProxyConf: LeaderProxyConf,
    @Named(ModuleNames.HOST_PORT) myHostPort: String): RequestForwarder = {
    val sslContext = SSLContextUtil.createSSLContext(httpConf.sslKeystorePath.get, httpConf.sslKeystorePassword.get)
    new JavaUrlConnectionRequestForwarder(sslContext, leaderProxyConf: LeaderProxyConf, myHostPort: String)
  }
}

class MarathonRestModule(httpService: HttpService) extends AbstractModule {

  override def configure(): Unit = {
    // Map some exceptions to HTTP responses
    bind(classOf[MarathonExceptionMapper]).asEagerSingleton()

    // Service API
    bind(classOf[SystemResource]).in(Scopes.SINGLETON)

    // V2 API
    bind(classOf[v2.AppsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.PodsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.TasksResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.QueueResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.GroupsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.InfoResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.LeaderResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.DeploymentsResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.SchemaResource]).in(Scopes.SINGLETON)
    bind(classOf[v2.PluginsResource]).in(Scopes.SINGLETON)

    bind(classOf[CORSFilter]).asEagerSingleton()
    bind(classOf[CacheDisablingFilter]).asEagerSingleton()
    bind(classOf[WebJarServlet]).in(Scopes.SINGLETON)
    bind(classOf[PublicServlet]).in(Scopes.SINGLETON)

    // other servlets
    bind(classOf[LogConfigServlet]).in(Scopes.SINGLETON)
    bind(classOf[ServiceStatus]).asEagerSingleton()
    bind(classOf[ServiceStatusServlet]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideRequestsLimiter(conf: MarathonConf): LimitConcurrentRequestsFilter = {
    new LimitConcurrentRequestsFilter(conf.maxConcurrentHttpConnections.get)
  }

  @Provides
  @Singleton
  def provideHttpService: MarathonHttpService =
    /** As a workaround, we delegate to the chaos provided httpService, since we have no control over this type */
    new AbstractIdleService with MarathonHttpService {
      override def startUp(): Unit =
        httpService.startUp()
      override def shutDown(): Unit =
        httpService.shutDown()
    }
}

trait MarathonHttpService extends Service
