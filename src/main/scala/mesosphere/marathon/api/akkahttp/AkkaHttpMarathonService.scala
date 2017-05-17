package mesosphere.marathon
package api.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpResponse }
import akka.stream.ActorMaterializer
import com.google.common.util.concurrent.AbstractIdleService
import com.typesafe.scalalogging.StrictLogging
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.MarathonHttpService

import scala.concurrent.Future
import scala.async.Async._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import mesosphere.marathon.api.akkahttp.Rejections.Message
import play.api.libs.json.Json

class AkkaHttpMarathonService(
    config: MarathonConf with HttpConf,
    resourceController: ResourceController,
    systemController: SystemController,
    v2Controller: V2Controller
)(
    implicit
    actorSystem: ActorSystem) extends AbstractIdleService with MarathonHttpService with StrictLogging {
  import actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  private var handler: Option[Future[Http.ServerBinding]] = None
  import Directives._
  import EntityMarshallers._
  implicit def rejectionHandler =
    RejectionHandler.newBuilder()
      .handle(LeaderDirectives.handleNonLeader)
      .handle(EntityMarshallers.handleNonValid)
      .handle(AuthDirectives.handleAuthRejections)
      .handle {
        case Rejections.EntityNotFound(message) => complete(NotFound -> message)
      }
      .result()
      .withFallback(RejectionHandler.default)
      .mapRejectionResponse {
        // Turn all simple string rejections into json format
        // Please note: akka-http has a lot of built in rejections, that are all send via plain text
        // Map RejectionResponse is the akka http suggested way of enforcing other content types.
        case res @ HttpResponse(_, _, HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, data), _) =>
          val message = Json.stringify(Json.toJson(Message(data.utf8String)))
          res.copy(entity = HttpEntity(ContentTypes.`application/json`, message))
        case response: HttpResponse => response
      }

  val route: Route = {
    val corsOrPass = config.accessControlAllowOrigin.get.map(corsResponse).getOrElse(pass)
    val compressOrPass = if (config.httpCompression()) encodeResponse else pass
    compressOrPass {
      noCache {
        corsOrPass {
          systemController.route ~
            resourceController.route ~
            pathPrefix("v2") {
              v2Controller.route
            }
        }
      }
    }
  }

  override def startUp(): Unit = synchronized {
    if (handler.isEmpty) {
      logger.info(s"Listening via Akka HTTP on ${config.httpPort()}")
      handler = Some(Http().bindAndHandle(route, "localhost", config.httpPort()))
    } else {
      logger.error("Service already started")
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def shutDown(): Unit = {
    val unset = synchronized {
      if (handler.isEmpty)
        None
      else {
        val oldHandler = handler
        handler = None
        oldHandler
      }
    }

    unset.foreach { oldHandlerF =>
      async {
        val oldHandler = await(oldHandlerF)
        logger.info(s"Shutting down Akka HTTP service on ${config.httpPort()}")
        val unbound = await(oldHandler.unbind())
        logger.info(s"Akka HTTP service on ${config.httpPort()} is stopped")
      }
    }
  }
}
