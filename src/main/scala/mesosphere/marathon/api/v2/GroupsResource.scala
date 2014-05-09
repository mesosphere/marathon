package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import javax.validation.Valid
import mesosphere.marathon.upgrade.UpgradeManager

@Path("v2/groups")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject()(upgradeManager: UpgradeManager) {

  @GET
  def list() = {
    List(group("1"), group("2"), group("3"))
  }

  @GET
  @Path("{id}")
  def group(@PathParam("id") id: String) = {
    Group(id, ScalingStrategy(Seq(AbsoluteStepping(13)), 123), Seq.empty)
  }

  @POST
  def install( @Valid group: Group ) = {
//    upgradeManager.install(group)
    group
  }

  @PUT
  @Path("{id}")
  def upgrade( @PathParam("id") id: String, @Valid group: Group ) = {
//    upgradeManager.upgrade(group, group)
  }

  @DELETE
  @Path("{id}")
  def delete( @PathParam("id") id: String ) = {
    //upgradeManager.delete(id)
  }
}
