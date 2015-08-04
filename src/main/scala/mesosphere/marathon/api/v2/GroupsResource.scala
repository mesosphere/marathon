package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.Response

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.{ V2Group, V2GroupUpdate }
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Group, GroupManager, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ ConflictingChangeException, MarathonConf }
import mesosphere.util.ThreadPoolContext.context
import play.api.libs.json.Json

@Path("v2/groups")
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class GroupsResource @Inject() (
    groupManager: GroupManager,
    val config: MarathonConf) extends RestResource {

  val ListApps = """^((?:.+/)|)apps$""".r
  val ListRootApps = """^apps$""".r
  val ListVersionsRE = """^(.+)/versions$""".r
  val ListRootVersionRE = """^versions$""".r
  val GetVersionRE = """^(.+)/versions/(.+)$""".r
  val GetRootVersionRE = """^versions/(.+)$""".r

  /**
    * Get root group.
    */
  @GET
  @Timed
  def root(): Group = result(groupManager.rootGroup())

  /**
    * Get a specific group, optionally with specific version
    * @param id the identifier of the group encoded as path
    * @return the group or the group versions.
    */
  @GET
  @Path("""{id:.+}""")
  @Timed
  def group(@PathParam("id") id: String): Response = {
    def groupResponse[T](id: PathId, fn: Group => T, version: Option[Timestamp] = None): Response = {
      result(version.map(groupManager.group(id, _)).getOrElse(groupManager.group(id))) match {
        case Some(group) => ok(fn(group))
        case None        => unknownGroup(id, version)
      }
    }
    id match {
      case ListApps(gid)              => groupResponse(gid.toRootPath, _.transitiveApps)
      case ListRootApps()             => groupResponse(PathId.empty, _.transitiveApps)
      case ListVersionsRE(gid)        => ok(result(groupManager.versions(gid.toRootPath)))
      case ListRootVersionRE()        => ok(result(groupManager.versions(PathId.empty)))
      case GetVersionRE(gid, version) => groupResponse(gid.toRootPath, V2Group(_), version = Some(Timestamp(version)))
      case GetRootVersionRE(version)  => groupResponse(PathId.empty, V2Group(_), version = Some(Timestamp(version)))
      case _                          => groupResponse(id.toRootPath, V2Group(_))
    }
  }

  /**
    * Create a new group.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @param body the request body as array byte buffer
    */
  @POST
  @Timed
  def create(@DefaultValue("false")@QueryParam("force") force: Boolean,
             body: Array[Byte]): Response = createWithPath("", force, body)

  /**
    * Create or update a group.
    * If the path to the group does not exist, it gets created.
    * @param id is the identifier of the the group to update.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @param body the request body as array byte buffer
    */
  @POST
  @Path("""{id:.+}""")
  @Timed
  def createWithPath(@PathParam("id") id: String,
                     @DefaultValue("false")@QueryParam("force") force: Boolean,
                     body: Array[Byte]): Response = {
    val update = Json.parse(body).as[V2GroupUpdate]
    BeanValidation.requireValid(ModelValidation.checkGroupUpdate(update, needsId = true))
    val effectivePath = update.id.map(_.canonicalPath(id.toRootPath)).getOrElse(id.toRootPath)
    val current = result(groupManager.rootGroup()).findGroup(_.id == effectivePath)
    if (current.isDefined)
      throw ConflictingChangeException(s"Group $effectivePath is already created. Use PUT to change this group.")
    val (deployment, path, version) = updateOrCreate(id.toRootPath, update, force)
    deploymentResult(deployment, Response.created(new URI(path.toString)))
  }

  @PUT
  @Timed
  def updateRoot(@DefaultValue("false")@QueryParam("force") force: Boolean,
                 @DefaultValue("false")@QueryParam("dryRun") dryRun: Boolean,
                 body: Array[Byte]): Response = {
    update("", force, dryRun, body)
  }

  /**
    * Create or update a group.
    * If the path to the group does not exist, it gets created.
    * @param id is the identifier of the the group to update.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @param dryRun only create the deployment without executing it.
    */
  @PUT
  @Path("""{id:.+}""")
  @Timed
  def update(@PathParam("id") id: String,
             @DefaultValue("false")@QueryParam("force") force: Boolean,
             @DefaultValue("false")@QueryParam("dryRun") dryRun: Boolean,
             body: Array[Byte]): Response = {
    val update = Json.parse(body).as[V2GroupUpdate]
    BeanValidation.requireValid(ModelValidation.checkGroupUpdate(update, needsId = false))
    if (dryRun) {
      val planFuture = groupManager.group(id.toRootPath).map { maybeOldGroup =>
        val oldGroup = maybeOldGroup.getOrElse(Group.empty)
        Json.obj(
          "steps" -> DeploymentPlan(oldGroup, update.apply(V2Group(oldGroup), Timestamp.now()).toGroup()).steps
        )
      }

      ok(result(planFuture).toString())
    }
    else {
      val (deployment, _, _) = updateOrCreate(id.toRootPath, update, force)
      deploymentResult(deployment)
    }
  }

  @DELETE
  @Timed
  def delete(@DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val version = Timestamp.now()
    val deployment = result(groupManager.update(
      PathId.empty,
      root => root.copy(apps = Set.empty, groups = Set.empty),
      version,
      force
    ))
    deploymentResult(deployment)
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
    result(groupManager.group(groupId)).fold(unknownGroup(groupId)) { group =>
      val version = Timestamp.now()
      val deployment = result(groupManager.update(groupId.parent, _.remove(groupId, version), version, force))
      deploymentResult(deployment)
    }
  }

  private def updateOrCreate(id: PathId, update: V2GroupUpdate, force: Boolean): (DeploymentPlan, PathId, Timestamp) = {
    val version = Timestamp.now()
    def groupChange(group: Group): Group = {
      val versionChange = update.version.map { updateVersion =>
        val versionedGroup = result(groupManager.group(id, updateVersion)).map(_.update(id, identity, version))
        versionedGroup.getOrElse(
          throw new IllegalArgumentException(s"Group $id not available in version $updateVersion")
        )
      }
      val scaleChange = update.scaleBy.map { scale =>
        group.updateApp(version) { app => app.copy(instances = (app.instances * scale).ceil.toInt) }
      }
      versionChange orElse scaleChange getOrElse update.apply(V2Group(group), version).toGroup()
    }

    val effectivePath = update.id.map(_.canonicalPath(id)).getOrElse(id)
    val deployment = result(groupManager.update(effectivePath, groupChange, version, force))
    (deployment, effectivePath, version)
  }
}
