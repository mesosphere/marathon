package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.api.v2.json.{ Formats, V2AppDefinition }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskQueue
import play.api.libs.json.Json

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject() (
    taskQueue: TaskQueue,
    val config: MarathonConf) extends RestResource {

  @GET
  @Timed
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(): Response = {
    import Formats._

    val queuedWithDelay = taskQueue.listWithDelay.map {
      case (task, delay) =>
        Json.obj(
          "app" -> V2AppDefinition(task.app),
          "count" -> task.count.get(),
          "delay" -> Json.obj(
            "timeLeftSeconds" -> math.max(0, delay.timeLeft.toSeconds), //deadlines can be negative
            "overdue" -> delay.isOverdue()
          )
        )
    }

    ok(Json.obj("queue" -> queuedWithDelay).toString())
  }

  @DELETE
  @Path("""{appId:.+}/delay""")
  def resetDelay(@PathParam("appId") appId: String): Response = {
    val id = appId.toRootPath
    taskQueue.listWithDelay.find(_._1.app.id == id).map {
      case (task, deadline) =>
        taskQueue.resetDelay(task.app)
        noContent
    }.getOrElse(notFound(s"application $appId not found in task queue"))
  }
}
