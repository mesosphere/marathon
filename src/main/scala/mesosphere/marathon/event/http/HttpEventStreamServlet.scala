package mesosphere.marathon.event.http

import java.util.UUID
import javax.inject.{ Inject, Named }
import javax.servlet.http.{ Cookie, HttpServletRequest, HttpServletResponse }

import akka.actor.ActorRef
import mesosphere.marathon.api.RequestFacade
import mesosphere.marathon.event.http.HttpEventStreamActor._
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.HttpResponse
import mesosphere.marathon.{ MarathonConf, ModuleNames }
import org.eclipse.jetty.servlets.EventSource.Emitter
import org.eclipse.jetty.servlets.{ EventSource, EventSourceServlet }

import scala.concurrent.{ Await, blocking }

/**
  * The Stream handle implementation for SSE.
  *
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
class HttpEventStreamServlet @Inject() (
  @Named(ModuleNames.HTTP_EVENT_STREAM) streamActor: ActorRef,
  conf: MarathonConf,
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

