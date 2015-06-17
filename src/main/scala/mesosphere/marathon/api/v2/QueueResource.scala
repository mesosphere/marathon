package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.api.v2.json.{ Formats, V2AppDefinition }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.PathId._
import play.api.libs.json.Json
import scala.concurrent.duration._

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject() (
    clock: Clock,
    launchQueue: LaunchQueue,
    val config: MarathonConf) extends RestResource {

  @GET
  @Timed
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(): Response = {
    import Formats._

    val queuedWithDelay = launchQueue.list.filter(_.waiting).map {
      case taskCount: LaunchQueue.QueuedTaskCount =>
        val timeLeft = clock.now() until taskCount.backOffUntil
        Json.obj(
          "app" -> V2AppDefinition(taskCount.app),
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
  def resetDelay(@PathParam("appId") appId: String): Response = {
    val id = appId.toRootPath
    launchQueue.list.find(_.app.id == id).map {
      case taskCount: LaunchQueue.QueuedTaskCount =>
        launchQueue.resetDelay(taskCount.app)
        noContent
    }.getOrElse(notFound(s"application $appId not found in task queue"))
  }
}
