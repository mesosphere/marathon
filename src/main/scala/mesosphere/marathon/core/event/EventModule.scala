package mesosphere.marathon
package core.event

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.impl.stream._
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import org.eclipse.jetty.servlets.EventSourceServlet
import org.slf4j.LoggerFactory

/**
  * Exposes everything necessary to provide an internal event stream, an HTTP events stream and HTTP event callbacks.
  */
class EventModule(
    eventBus: EventStream,
    actorSystem: ActorSystem,
    conf: EventConf,
    electionService: ElectionService,
    authenticator: Authenticator,
    authorizer: Authorizer)(implicit val materializer: Materializer) {
  val log = LoggerFactory.getLogger(getClass.getName)

  lazy val httpEventStreamActor: ActorRef = {
    val outstanding = conf.eventStreamMaxOutstandingMessages()

    def handleStreamProps(handle: HttpEventStreamHandle): Props =
      Props(new HttpEventStreamHandleActor(handle, eventBus, outstanding))

    actorSystem.actorOf(
      Props(
        new HttpEventStreamActor(
          electionService.leaderStateEvents,
          new HttpEventStreamActorMetrics(),
          handleStreamProps)
      ),
      "HttpEventStream"
    )
  }

  lazy val httpEventStreamServlet: EventSourceServlet = {
    new HttpEventStreamServlet(httpEventStreamActor, conf, authenticator, authorizer)
  }
}
