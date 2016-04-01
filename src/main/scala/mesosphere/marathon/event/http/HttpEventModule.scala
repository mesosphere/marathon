package mesosphere.marathon.event.http

import java.util.concurrent.Executors

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.name.Named
import com.google.inject.{ AbstractModule, Provides, Scopes }
import mesosphere.marathon.ModuleNames.STORE_EVENT_SUBSCRIBERS
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.event.{ MarathonSubscriptionEvent, Subscribe }
import mesosphere.marathon.state.EntityStore
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

trait HttpEventConfiguration extends ScallopConf {

  lazy val httpEventEndpoints = opt[String]("http_endpoints",
    descr = "The URLs of the event endpoints added to the current list of subscribers on startup. " +
      "You can manage this list during runtime by using the /v2/eventSubscriptions API endpoint.",
    required = false,
    noshort = true).map(parseHttpEventEndpoints)

  lazy val httpEventCallbackSlowConsumerTimeout = opt[Long]("http_event_callback_slow_consumer_timeout",
    descr = "A http event callback consumer is considered slow, if the delivery takes longer than this timeout (ms)",
    required = false,
    noshort = true,
    default = Some(10.seconds.toMillis)
  )

  private[this] def parseHttpEventEndpoints(str: String): List[String] =
    str.split(',').map(_.trim).toList

  def slowConsumerTimeout: FiniteDuration = httpEventCallbackSlowConsumerTimeout().millis
}

class HttpEventModule(httpEventConfiguration: HttpEventConfiguration) extends AbstractModule {

  val log = LoggerFactory.getLogger(getClass.getName)

  def configure() {
    bind(classOf[HttpEventActor.HttpEventActorMetrics]).in(Scopes.SINGLETON)
    bind(classOf[HttpCallbackEventSubscriber]).asEagerSingleton()
    bind(classOf[HttpCallbackSubscriptionService]).in(Scopes.SINGLETON)
    bind(classOf[HttpEventConfiguration]).toInstance(httpEventConfiguration)
  }

  @Provides
  @Named(HttpEventModule.StatusUpdateActor)
  def provideStatusUpdateActor(system: ActorSystem,
                               @Named(HttpEventModule.SubscribersKeeperActor) subscribersKeeper: ActorRef,
                               metrics: HttpEventActor.HttpEventActorMetrics,
                               clock: Clock): ActorRef = {
    system.actorOf(Props(new HttpEventActor(httpEventConfiguration, subscribersKeeper, metrics, clock)))
  }

  @Provides
  @Named(HttpEventModule.SubscribersKeeperActor)
  def provideSubscribersKeeperActor(conf: HttpEventConfiguration,
                                    system: ActorSystem,
                                    @Named(STORE_EVENT_SUBSCRIBERS) store: EntityStore[EventSubscribers]): ActorRef = {
    implicit val timeout = HttpEventModule.timeout
    val local_ip = java.net.InetAddress.getLocalHost.getHostAddress

    val actor = system.actorOf(Props(new SubscribersKeeperActor(store)))
    conf.httpEventEndpoints.get foreach { urls =>
      log.info(s"http_endpoints($urls) are specified at startup. Those will be added to subscribers list.")
      urls foreach { url =>
        val f = (actor ? Subscribe(local_ip, url)).mapTo[MarathonSubscriptionEvent]
        f.onFailure {
          case th: Throwable =>
            log.warn(s"Failed to add $url to event subscribers. exception message => ${th.getMessage}")
        }(ExecutionContext.global)
      }
    }

    actor
  }
}

object HttpEventModule {
  final val StatusUpdateActor = "EventsActor"
  final val SubscribersKeeperActor = "SubscriberKeeperActor"

  //TODO(everpeace) this should be configurable option?
  val timeout = Timeout(10 seconds)
}

