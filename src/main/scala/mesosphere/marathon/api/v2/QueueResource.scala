package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.plugin.auth.{ Authorizer, Authenticator, UpdateAppOrGroup }
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
  def index(@Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    import Formats._

    doIfAuthenticated(req, resp) { implicit identity =>
      val queuedWithDelay = launchQueue.list.filter(t => t.inProgress && isAllowedToView(t.app.id)).map {
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
  }

  @DELETE
  @Path("""{appId:.+}/delay""")
  def resetDelay(@PathParam("appId") appId: String,
                 @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    val id = appId.toRootPath
    doIfAuthorized(req, resp, UpdateAppOrGroup, id) { implicit identity =>
      launchQueue.list.find(_.app.id == id).map {
        case taskCount: LaunchQueue.QueuedTaskInfo =>
          launchQueue.resetDelay(taskCount.app)
          noContent
      }.getOrElse(notFound(s"application $appId not found in task queue"))
    }
  }
}
