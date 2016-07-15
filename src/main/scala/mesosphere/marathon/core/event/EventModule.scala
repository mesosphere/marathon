package mesosphere.marathon.core.event

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.pattern.ask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.impl.history.HistoryActor
import mesosphere.marathon.core.event.impl.http._
import mesosphere.marathon.core.event.impl.stream._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ EntityStore, TaskFailureRepository }
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

class EventModule(
    eventBus: EventStream,
    actorSystem: ActorSystem,
    conf: EventConf,
    metrics: Metrics,
    clock: Clock,
    eventSubscribersStore: EntityStore[EventSubscribers],
    taskFailureRepository: TaskFailureRepository,
    electionService: ElectionService) {
  val log = LoggerFactory.getLogger(getClass.getName)

  private lazy val httpCallbacksEnabled: Boolean = {
    conf.eventSubscriber.get match {
      case Some("http_callback") =>
        log.info("Using HttpCallbackEventSubscriber for event notification")
        true
      case _ =>
        log.info("Event notification disabled.")
        false
    }
  }

  private[this] lazy val statusUpdateActor: ActorRef =
    actorSystem.actorOf(Props(
      new HttpEventActor(conf, subscribersKeeperActor, new HttpEventActor.HttpEventActorMetrics(metrics), clock))
    )

  private[this] lazy val subscribersKeeperActor: ActorRef = {
    implicit val timeout = conf.eventRequestTimeout
    val local_ip = java.net.InetAddress.getLocalHost.getHostAddress

    val actor = actorSystem.actorOf(Props(new SubscribersKeeperActor(eventSubscribersStore)))
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

    eventBus.subscribe(statusUpdateActor, classOf[MarathonEvent])

    actor
  }

  lazy val httpCallbackSubscriptionService: Option[HttpCallbackSubscriptionService] = {
    if (httpCallbacksEnabled) Some(new HttpCallbackSubscriptionService(subscribersKeeperActor, eventBus, conf))
    else None
  }

  lazy val historyActorProps: Props = Props(new HistoryActor(eventBus, taskFailureRepository))

  lazy val httpEventStreamActor: ActorRef = {
    val outstanding = conf.eventStreamMaxOutstandingMessages()

    def handleStreamProps(handle: HttpEventStreamHandle): Props =
      Props(new HttpEventStreamHandleActor(handle, eventBus, outstanding))

    actorSystem.actorOf(
      Props(
        new HttpEventStreamActor(
          electionService,
          new HttpEventStreamActorMetrics(metrics),
          handleStreamProps)
      ),
      "HttpEventStream"
    )
  }

}
