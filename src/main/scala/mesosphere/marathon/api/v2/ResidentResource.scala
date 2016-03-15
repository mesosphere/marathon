package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.{ BadRequestException, MarathonConf }
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType, RestResource }
import mesosphere.marathon.core.appinfo.AppSelector
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.state._

import scala.collection.immutable.Seq
import scala.concurrent.Future

@Path("v2/residents")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class ResidentResource @Inject() (
    clock: Clock,
    taskTracker: TaskTracker,
    taskRepository: TaskRepository,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends RestResource with AuthResource {

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@PathParam("id") id: String,
             @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val taskId = Task.Id(id)

    import scala.concurrent.ExecutionContext.Implicits.global
    val response = for {
      maybeTask <- taskTracker.task(taskId)
      updated <- setTimeout(maybeTask)
    } yield ok(jsonObjString("task" -> updated.taskId.idString))

    result(response)
  }

  private[this] def setTimeout(task: Option[Task]): Future[Task] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    task match {
      case Some(reserved: Task.Reserved) =>
        val timeout = Task.Reservation.Timeout(
          initiated = clock.now(),
          deadline = clock.now(),
          reason = Task.Reservation.Timeout.Reason.ReservationTimeout)
        val updated = reserved.copy(reservation = reserved.reservation.copy(
          state = Task.Reservation.State.Garbage(Some(timeout))))
        taskRepository.store(TaskSerializer.toProto(updated)).map(TaskSerializer.fromProto)

      case _ => throw new BadRequestException("Only suspended resident tasks can be deleted")
    }
  }

  def allAuthorized(implicit identity: Identity): AppSelector = new AppSelector {
    override def matches(app: AppDefinition): Boolean = isAuthorized(ViewApp, app)
  }

  def selectAuthorized(fn: => AppSelector)(implicit identity: Identity): AppSelector = {
    val authSelector = new AppSelector {
      override def matches(app: AppDefinition): Boolean = isAuthorized(ViewApp, app)
    }
    AppSelector.forall(Seq(authSelector, fn))
  }
}
