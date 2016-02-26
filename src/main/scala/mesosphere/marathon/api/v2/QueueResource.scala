package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.{ UnknownAppException, MarathonConf }
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateApp, ViewApp }
import mesosphere.marathon.state.PathId._
import play.api.libs.json.Json

import scala.concurrent.duration._

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
  def index(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    import Formats._

    val queuedWithDelay = launchQueue.list.filter(t => t.inProgress && isAuthorized(ViewApp, t.app)).map {
      case taskCount: LaunchQueue.QueuedTaskInfo =>
        val timeLeft = clock.now() until taskCount.backOffUntil
        Json.obj(
          "app" -> taskCount.app,
          "count" -> taskCount.tasksLeftToLaunch,
          "delay" -> Json.obj(
            "timeLeftSeconds" -> math.max(0, timeLeft.toSeconds), //deadlines can be negative
            "overdue" -> (timeLeft < 0.seconds)
          )
        )
    }
    ok(Json.obj("queue" -> queuedWithDelay).toString())
  }

  @DELETE
  @Path("""{appId:.+}/delay""")
  def resetDelay(@PathParam("appId") id: String,
                 @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val appId = id.toRootPath
    val maybeApp = launchQueue.list.find(_.app.id == appId).map(_.app)
    withAuthorization(UpdateApp, maybeApp, notFound(s"Application $appId not found in tasks queue.")) { app =>
      launchQueue.resetDelay(app)
      noContent
    }
  }
}
