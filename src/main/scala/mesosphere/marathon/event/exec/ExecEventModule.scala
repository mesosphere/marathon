package mesosphere.marathon.event.exec

import scala.language.postfixOps
import com.google.inject.{Scopes, Singleton, Provides, AbstractModule}
import akka.actor.{Props, ActorRef, ActorSystem}
import akka.pattern.ask
import com.google.inject.name.Named
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import org.rogach.scallop.ScallopConf
import java.util.logging.Logger
import scala.concurrent.duration._
import akka.util.Timeout
import org.apache.mesos.state.State
import mesosphere.marathon.state.MarathonStore
import mesosphere.marathon.Main
import mesosphere.marathon.event.{MarathonSubscriptionEvent, Subscribe}

trait ExecEventConfiguration extends ScallopConf {

  lazy val execEventEndpoints = opt[List[String]]("exec_endpoints",
    descr = "The commands for the event endpoints master",
    required = false,
    noshort = true)
}

class ExecEventModule extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)

  def configure() {
    bind(classOf[ExecCallbackEventSubscriber]).asEagerSingleton()
    bind(classOf[ExecCallbackSubscriptionService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = {
    ActorSystem("MarathonEvents")
  }

  @Provides
  @Named(ExecEventModule.StatusUpdateActor)
  def provideStatusUpdateActor(system: ActorSystem,
                               @Named(ExecEventModule.SubscribersKeeperActor)
                               subscribersKeeper: ActorRef): ActorRef = {
    system.actorOf(Props(new ExecEventActor(subscribersKeeper)))
  }

  @Provides
  @Named(ExecEventModule.SubscribersKeeperActor)
  def provideSubscribersKeeperActor(system: ActorSystem,
                                    store: MarathonStore[EventSubscribers]): ActorRef = {
    implicit val timeout = ExecEventModule.timeout
    implicit val ec = ExecEventModule.executionContext
    val local_ip = java.net.InetAddress.getLocalHost().getHostAddress()

    val actor = system.actorOf(Props(new SubscribersKeeperActor(store)))
    Main.conf.execEventEndpoints.get map {
      urls =>
        log.info(s"exec_endpoints(${urls}) are specified at startup. Those will be added to subscribers list.")
        urls.foreach{ url =>
          val f = (actor ? Subscribe(local_ip, url)).mapTo[MarathonSubscriptionEvent]
          f.onFailure {
            case th: Throwable =>
              log.warning(s"Failed to add ${url} to event subscribers. exception message => ${th.getMessage}")
          }
        }
    }

    actor
  }

  @Provides
  @Singleton
  def provideCallbackUrlsStore(state: State): MarathonStore[EventSubscribers] = {
    new MarathonStore[EventSubscribers](state, () => new EventSubscribers(Set.empty[String]), "events:")
  }

}

object ExecEventModule {
  final val StatusUpdateActor = "EventsActor"
  final val SubscribersKeeperActor = "SubscriberKeeperActor"

  val executorService = Executors.newCachedThreadPool()
  val executionContext = ExecutionContext.fromExecutorService(executorService)

  //TODO(everpeace) this should be configurable option?
  val timeout = Timeout(10 seconds)
}

