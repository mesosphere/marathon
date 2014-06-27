package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.Responses
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Group, GroupManager, PathId, Timestamp }
import mesosphere.util.ThreadPoolContext.context

import scala.concurrent.{ Await, Awaitable }

@Path("v2/groups")
@Produces(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject() (groupManager: GroupManager, config: MarathonConf) extends ModelValidation {

  val ListVersionsRE = """^(.+)/versions$""".r
  val GetVersionRE = """^(.+)/versions/(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)$""".r

  /**
    * Get root group.
    */
  @GET
  @Timed
  def root(): Group = result(groupManager.root)

  /**
    * Get a specific group, optionally with specific version
    * @param id the identifier of the group encoded as path
    * @return the group or the group versions.
    */
  @GET
  @Path("""{id:.+}""")
  @Timed
  def group(@PathParam("id") id: String): Response = {
    def groupResponse(g: Option[Group]) = g match {
      case Some(group) => Response.ok(group).build()
      case None        => Responses.unknownGroup(id.toRootPath)
    }
    id match {
      case ListVersionsRE(gid)        => Response.ok(result(groupManager.versions(gid.toRootPath))).build()
      case GetVersionRE(gid, version) => groupResponse(result(groupManager.group(gid.toRootPath, Timestamp(version))))
      case _                          => groupResponse(result(groupManager.group(id.toRootPath)))
    }
  }

  /**
    * Create a new group.
    * @param update the group is encoded in the update.
    */
  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Timed
  def create(update: GroupUpdate): Response = {
    requireValid(checkGroup(update))
    val (path, version) = updateOrCreate(PathId.empty, update, force = false)
    Response.created(new URI(path.toString)).entity(Map("version" -> version)).build()
  }

  /**
    * Create or update a group.
    * If the path to the group does not exist, it gets created.
    * @param id is the identifier of the the group to update.
    * @param update is the update to apply on the group specified by the given path.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    */
  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("""{id:.+}""")
  @Timed
  def createUpdate(@PathParam("id") id: String,
                   update: GroupUpdate,
                   @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    requireValid(checkGroup(update))
    val (path, version) = updateOrCreate(id.toRootPath, update, force)
    Response.created(new URI(path.toString)).entity(Map("version" -> version)).build()
  }

  @PUT
  @Consumes(Array(MediaType.APPLICATION_JSON)) //@Path("""{path:(?!.*/version/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$).+}""")
  @Timed
  def updateRoot(update: GroupUpdate,
                 @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    requireValid(checkGroup(update))
    val (_, version) = updateOrCreate(PathId.empty, update, force)
    Response.ok(Map("version" -> version)).build()
  }

  /**
    * Create or update a group.
    * If the path to the group does not exist, it gets created.
    * @param id is the identifier of the the group to update.
    * @param update is the update to apply on the group specified by the given path.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    */
  @PUT
  @Consumes(Array(MediaType.APPLICATION_JSON)) //@Path("""{path:(?!.*/version/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$).+}""")
  @Path("""{id:.+}""")
  @Timed
  def update(@PathParam("id") id: String,
             update: GroupUpdate,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    requireValid(checkGroup(update))
    val (_, version) = updateOrCreate(id.toRootPath, update, force)
    Response.ok(Map("version" -> version)).build()
  }

  /**
    * Delete a specific subtree or a complete tree.
    * @param id the identifier of the group to delete encoded as path
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @return A version response, which defines the resulting change.
    */
  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@PathParam("id") id: String,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val groupId = id.toRootPath
    val version = Timestamp.now()
    groupManager.update(groupId.parent, _.remove(groupId, version), version, force)
    Response.ok(Map("version" -> version)).build()
  }

  private def updateOrCreate(id: PathId, update: GroupUpdate, force: Boolean): (PathId, Timestamp) = {
    requireValid(checkGroup(update))
    val version = Timestamp.now()
    def groupChange(group: Group): Group = {
      val versionChange = update.version.map { version =>
        result(groupManager.group(id, version)).getOrElse(throw new IllegalArgumentException(s"Group $id not available in version $version"))
      }
      val scaleChange = update.scale.map { scale =>
        group.transitiveApps.foldLeft(group) { (changedGroup, app) =>
          changedGroup.updateApp(app.id, _.copy(instances = (app.instances * scale).ceil.toInt), version)
        }
      }
      versionChange orElse scaleChange getOrElse update.apply(group, version)
    }

    val effectivePath = update.id.map(_.canonicalPath(id)).getOrElse(id)
    groupManager.update(effectivePath, groupChange, version, force)
    (effectivePath, version)
  }

  private def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)
}
