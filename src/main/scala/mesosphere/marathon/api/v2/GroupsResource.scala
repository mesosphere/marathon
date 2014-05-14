package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import javax.validation.Valid
import mesosphere.marathon.state.GroupManager
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

@Path("v2/groups")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject()(groupManager: GroupManager) {

  @GET
  def list() : Iterable[Group] = groupManager.list()

  @GET
  @Path("{id}")
  def group(@PathParam("id") id: String) : Option[Group] = groupManager.group(id)

  @POST
  def create( @Valid group: Group ) : Group = groupManager.create(group)

  @PUT
  @Path("{id}")
  def upgrade( @PathParam("id") id: String, @Valid group: Group ) : Group = groupManager.upgrade(id, group)

  @DELETE
  @Path("{id}")
  def delete( @PathParam("id") id: String ) : Boolean = groupManager.expunge(id)

  //map from Future[T] to T since jersey can not handle futures
  private implicit def result[T](in:Future[T]) : T = Await.result(in, 30 seconds)
}
