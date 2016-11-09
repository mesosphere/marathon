package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.AppDefinition
import play.api.libs.json.{ JsObject, Json }

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject() (
    clock: Clock,
    launchQueue: LaunchQueue,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val config: MarathonConf) extends AuthResource {

  @GET
  @Timed
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(@Context req: HttpServletRequest, @QueryParam("embed") embed: java.util.Set[String]): Response = authenticated(req) { implicit identity =>
    val embedLastUnusedOffers = embed.contains(QueueResource.EmbedLastUnusedOffers)
    val infos = launchQueue.listWithStatistics.filter(t => t.inProgress && isAuthorized(ViewRunSpec, t.runSpec))

    // FIXME: replace the rest of this method with the following line, once AppConversion is implemented
    // ok(Raml.toRaml((info, embed, clock)))
    val result = infos.map { info =>
      import mesosphere.marathon.api.v2.json.Formats._
      val queueItem = Json.toJson(Raml.toRaml((info, embedLastUnusedOffers, clock))).as[JsObject]
      info.runSpec match {
        case app: AppDefinition => queueItem ++ Json.obj("app" -> Json.toJson(app))
        case _ => queueItem
      }
    }
    ok(Json.obj("queue" -> result))
  }

  @DELETE
  @Path("""{appId:.+}/delay""")
  def resetDelay(
    @PathParam("appId") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val appId = id.toRootPath
    val maybeApp = launchQueue.list.find(_.runSpec.id == appId).map(_.runSpec)
    withAuthorization(UpdateRunSpec, maybeApp, notFound(s"Application $appId not found in tasks queue.")) { app =>
      launchQueue.resetDelay(app)
      noContent
    }
  }
}

object QueueResource {
  val EmbedLastUnusedOffers = "lastUnusedOffers"
}
