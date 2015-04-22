package mesosphere.marathon.event.http

import scala.language.postfixOps
import com.codahale.metrics.MetricRegistry
import com.google.inject.{ Scopes, Singleton, Provides, AbstractModule }
import akka.actor.{ Props, ActorRef, ActorSystem }
import akka.pattern.ask
import com.google.inject.name.Named
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import org.rogach.scallop.ScallopConf
import org.apache.log4j.Logger
import scala.concurrent.duration._
import akka.util.Timeout
import org.apache.mesos.state.State
import mesosphere.marathon.state.MarathonStore
import mesosphere.marathon.event.{ MarathonSubscriptionEvent, Subscribe }
import mesosphere.marathon.MarathonConf

trait HttpEventConfiguration extends ScallopConf {

  lazy val httpEventEndpoints = opt[List[String]]("http_endpoints",
    descr = "The URLs of the event endpoints master",
    required = false,
    noshort = true)
}

class HttpEventModule(httpEventConfiguration: HttpEventConfiguration) extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)

  def configure() {
    bind(classOf[HttpCallbackEventSubscriber]).asEagerSingleton()
    bind(classOf[HttpCallbackSubscriptionService]).in(Scopes.SINGLETON)
    bind(classOf[HttpEventConfiguration]).toInstance(httpEventConfiguration)
  }

  @Provides
  @Named(HttpEventModule.StatusUpdateActor)
  def provideStatusUpdateActor(system: ActorSystem,
                               @Named(HttpEventModule.SubscribersKeeperActor) subscribersKeeper: ActorRef): ActorRef = {
    system.actorOf(Props(new HttpEventActor(subscribersKeeper)))
  }

  @Provides
  @Named(HttpEventModule.SubscribersKeeperActor)
  def provideSubscribersKeeperActor(conf: HttpEventConfiguration,
                                    system: ActorSystem,
                                    store: MarathonStore[EventSubscribers]): ActorRef = {
    implicit val timeout = HttpEventModule.timeout
    implicit val ec = HttpEventModule.executionContext
    val local_ip = java.net.InetAddress.getLocalHost.getHostAddress

    val actor = system.actorOf(Props(new SubscribersKeeperActor(store)))
    conf.httpEventEndpoints.get foreach { urls =>
      log.info(s"http_endpoints($urls) are specified at startup. Those will be added to subscribers list.")
      urls foreach { url =>
        val f = (actor ? Subscribe(local_ip, url)).mapTo[MarathonSubscriptionEvent]
        f.onFailure {
          case th: Throwable =>
            log.warn(s"Failed to add $url to event subscribers. exception message => ${th.getMessage}")
        }
      }
    }

    actor
  }

  @Provides
  @Singleton
  def provideCallbackUrlsStore(conf: MarathonConf,
                               state: State, registry: MetricRegistry): MarathonStore[EventSubscribers] =
    new MarathonStore[EventSubscribers](conf, state, registry, () => new EventSubscribers(Set.empty[String]), "events:")
}

object HttpEventModule {
  final val StatusUpdateActor = "EventsActor"
  final val SubscribersKeeperActor = "SubscriberKeeperActor"

  val executorService = Executors.newCachedThreadPool()
  val executionContext = ExecutionContext.fromExecutorService(executorService)

  //TODO(everpeace) this should be configurable option?
  val timeout = Timeout(10 seconds)
}

