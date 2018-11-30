package mesosphere.marathon
package core.event

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.impl.stream._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer}
import org.eclipse.jetty.servlets.EventSourceServlet

/**
  * Exposes everything necessary to provide an internal event stream, an HTTP events stream and HTTP event callbacks.
  */
class EventModule(
    metrics: Metrics,
    eventBus: EventStream,
    actorSystem: ActorSystem,
    conf: EventConf,
    deprecatedFeatureSet: DeprecatedFeatureSet,
    electionService: ElectionService,
    authenticator: Authenticator,
    authorizer: Authorizer)(implicit val materializer: Materializer) {

  lazy val httpEventStreamActor: ActorRef = {
    val outstanding = conf.eventStreamMaxOutstandingMessages()

    def handleStreamProps(handle: HttpEventStreamHandle): Props =
      Props(new HttpEventStreamHandleActor(handle, eventBus, outstanding))

    actorSystem.actorOf(
      Props(
        new HttpEventStreamActor(
          electionService.leadershipTransitionEvents,
          new HttpEventStreamActorMetrics(metrics),
          handleStreamProps)
      ),
      "HttpEventStream"
    )
  }

  lazy val httpEventStreamServlet: EventSourceServlet = {
    new HttpEventStreamServlet(
      metrics,
      httpEventStreamActor,
      conf,
      authenticator,
      authorizer)
  }
}
