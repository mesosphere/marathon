package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import javax.validation.Valid
import mesosphere.marathon.state.GroupManager
import scala.concurrent.Await.result
import scala.concurrent.duration._
import mesosphere.marathon.api.PATCH

@Path("v2/groups")
@Consumes(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject()(groupManager: GroupManager) {

  val defaultWait = 3.seconds

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def list() : Iterable[Group] = result(groupManager.list(), defaultWait)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  def group(@PathParam("id") id: String) : Option[Group] = result(groupManager.group(id), defaultWait)

  @POST
  def create( @Valid group: Group ) : Response = {
    groupManager.create(group)
    Response.noContent().build()
  }

  @PUT
  @Path("{id}")
  def upgrade( @PathParam("id") id: String, @Valid group: Group ) : Response = {
    groupManager.upgrade(id, group)
    Response.noContent().build()
  }

  @PATCH
  @Path("{id}")
  def patch( @PathParam("id") id:String, @Valid update: GroupUpdate) : Response = {
    groupManager.patch(id, update.apply)
    Response.noContent().build()
  }

  @DELETE
  @Path("{id}")
  def delete( @PathParam("id") id: String ) : Response = {
    val response = if (result(groupManager.expunge(id), defaultWait)) Response.ok else Response.noContent()
    response.build()
  }
}
