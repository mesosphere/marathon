package mesosphere.marathon.event.http

import java.util.UUID
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest

import akka.actor.ActorRef
import mesosphere.marathon.ModuleNames
import mesosphere.marathon.event.http.HttpEventStreamActor._
import org.eclipse.jetty.servlets.EventSource.Emitter
import org.eclipse.jetty.servlets.{ EventSource, EventSourceServlet }

import scala.concurrent.blocking

/**
  * The Stream handle implementation for SSE.
  * @param request the initial http request.
  * @param emitter the emitter to emit data
  */
class HttpEventSSEHandle(request: HttpServletRequest, emitter: Emitter) extends HttpEventStreamHandle {

  lazy val id: String = UUID.randomUUID().toString

  override def remoteAddress: String = request.getRemoteAddr

  override def close(): Unit = emitter.close()

  override def sendEvent(event: String, message: String): Unit = blocking(emitter.event(event, message))

  override def toString: String = s"HttpEventSSEHandle($id on $remoteAddress)"
}

/**
  * Handle a server side event client stream by delegating events to the stream actor.
  */
class HttpEventStreamServlet @Inject() (@Named(ModuleNames.HTTP_EVENT_STREAM) streamActor: ActorRef)
    extends EventSourceServlet {

  override def newEventSource(request: HttpServletRequest): EventSource = new EventSource {
    @volatile private var handler: Option[HttpEventSSEHandle] = None

    override def onOpen(emitter: Emitter): Unit = {
      val handle = new HttpEventSSEHandle(request, emitter)
      this.handler = Some(handle)
      streamActor ! HttpEventStreamConnectionOpen(handle)
    }

    override def onClose(): Unit = {
      handler.foreach(streamActor ! HttpEventStreamConnectionClosed(_))
      handler = None
    }
  }
}

