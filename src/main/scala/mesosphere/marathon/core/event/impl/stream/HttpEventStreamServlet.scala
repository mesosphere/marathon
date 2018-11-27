package mesosphere.marathon
package core.event.impl.stream

import java.util.UUID
import javax.servlet.http.{Cookie, HttpServletRequest, HttpServletResponse}
import akka.actor.ActorRef
import mesosphere.marathon.api.{HttpTransferMetricsHandler, RequestFacade}
import mesosphere.marathon.core.event.{EventConf, MarathonEvent}
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamActor._
import mesosphere.marathon.metrics.{Counter, Metrics}
import mesosphere.marathon.metrics.current.UnitOfMeasurement
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.HttpResponse
import org.eclipse.jetty.servlets.EventSource.Emitter
import org.eclipse.jetty.servlets.{EventSource, EventSourceServlet}
import scala.concurrent.{Await, blocking}

/**
  * The Stream handle implementation for SSE.
  *
  * @param request the initial http request.
  * @param emitter the emitter to emit data
  */
class HttpEventSSEHandle(
    metrics: Metrics,
    request: HttpServletRequest,
    emitter: Emitter) extends HttpEventStreamHandle {

  lazy val id: String = UUID.randomUUID().toString

  private val responsesEventStreamSizeMetric: Counter =
    metrics.counter("http.responses.event-stream.size", UnitOfMeasurement.Memory)

  private val sseFrameOverhead: Long = 16L
  private val subscribedEventTypes = request.getParameterMap.getOrDefault("event_type", Array.empty).toSet

  /**
    * Calculates the number of bytes sent (including the SSE framing) and updates
    * the `bytesWrittenMetric` accordingly.
    *
    * Note that an SSE frame has the following structure:
    *
    * "name: <name>\n
    *  data: <payload>\n
    *  \n
    * "
    *
    * @param eventName The name of the event, used for estimating the "name" field length
    * @param payload The event payload, used for estimating the "data" field length
    * @return Passes through the `payload` argument
    */
  private def measureFrameBytesSent(eventName: String, payload: String): Unit = {
    val overhead: Long = sseFrameOverhead + eventName.length.toLong
    responsesEventStreamSizeMetric.increment(payload.length.toLong + overhead)
  }

  def subscribed(eventType: String): Boolean = {
    subscribedEventTypes.isEmpty || subscribedEventTypes.contains(eventType)
  }

  override def remoteAddress: String = request.getRemoteAddr

  override def close(): Unit = emitter.close()

  override def sendEvent(event: MarathonEvent): Unit = {
    if (subscribed(event.eventType)) {
      val payload = event.jsonString
      measureFrameBytesSent(event.eventType, payload)
      blocking(emitter.event(event.eventType, payload))
    }
  }

  override def toString: String = s"HttpEventSSEHandle($id on $remoteAddress on event types from $subscribedEventTypes)"
}

/**
  * Handle a server side event client stream by delegating events to the stream actor.
  */
class HttpEventStreamServlet(
    metrics: Metrics,
    streamActor: ActorRef,
    conf: EventConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer)
  extends EventSourceServlet {

  override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    val requestFacade = new RequestFacade(request)
    val maybeIdentity = Await.result(authenticator.authenticate(requestFacade), conf.zkTimeoutDuration)

    def withResponseFacade(fn: HttpResponse => Unit): Unit = {
      val facade = new HttpResponse {
        override def body(mediaType: String, bytes: Array[Byte]): Unit = {
          response.setHeader("Content-Type", mediaType)
          response.getWriter.write(new String(bytes))
        }

        override def sendRedirect(url: String): Unit = {
          response.sendRedirect(url)
        }

        override def header(header: String, value: String): Unit = {
          response.addHeader(header, value)
        }

        override def cookie(name: String, value: String, maxAge: Int, secure: Boolean): Unit = {
          val cookie = new Cookie(name, value)
          cookie.setMaxAge(maxAge)
          cookie.setSecure(secure)
          response.addCookie(cookie)
        }

        override def status(code: Int): Unit = {
          response.setStatus(code)
        }
      }
      fn(facade)
    }

    def isAuthorized(identity: Identity): Boolean = {
      authorizer.isAuthorized(identity, ViewResource, AuthorizedResource.Events)
    }

    maybeIdentity match {
      case Some(identity) if isAuthorized(identity) =>
        super.doGet(request, response)
      case Some(identity) =>
        withResponseFacade(authorizer.handleNotAuthorized(identity, _))
      case None =>
        withResponseFacade(authenticator.handleNotAuthenticated(requestFacade, _))
    }
  }

  override def doTrace(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
  }

  override def doOptions(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.setHeader("Allow", "GET, HEAD, OPTIONS")
  }

  override def newEventSource(request: HttpServletRequest): EventSource = new EventSource {
    @volatile private var handler: Option[HttpEventSSEHandle] = None

    override def onOpen(emitter: Emitter): Unit = {
      // We don't want to count this response towards the http metrics as it could be quite large by the time it closes.
      // Also, the serialization for events is done once for all consumers; we should track the data with a separate
      // metric.
      HttpTransferMetricsHandler.exclude(request)

      val handle = new HttpEventSSEHandle(metrics, request, emitter)
      this.handler = Some(handle)
      streamActor ! HttpEventStreamConnectionOpen(handle)
    }

    override def onClose(): Unit = {
      handler.foreach(streamActor ! HttpEventStreamConnectionClosed(_))
      handler = None
    }
  }
}

