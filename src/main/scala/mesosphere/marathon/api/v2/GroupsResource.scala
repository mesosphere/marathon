package mesosphere.marathon
package api.v2

import java.net.URI
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, Response }

import akka.stream.Materializer
import mesosphere.marathon.api.v2.InfoEmbedResolver._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.appinfo.{ GroupInfo, GroupInfoService, Selector }
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{ GroupConversion, Raml }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.stream.Sink
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.matching.Regex

@Path("v2/groups")
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class GroupsResource @Inject() (
    groupManager: GroupManager,
    infoService: GroupInfoService,
    val config: MarathonConf)(implicit
  val authenticator: Authenticator,
    val authorizer: Authorizer,
    mat: Materializer) extends AuthResource {

  import GroupsResource._
  import Normalization._

  /** convert app to canonical form */
  private implicit val appNormalization: Normalization[raml.App] = {
    val appNormalizationConfig = AppNormalization.Configuration(
      config.defaultNetworkName.get,
      config.mesosBridgeName())
    AppsResource.appNormalization(AppsResource.NormalizationConfig(config.availableFeatures, appNormalizationConfig))
  }

  /**
    * For backward compatibility, we embed always apps, pods, and groups if nothing is specified.
    */
  val defaultEmbeds = Set(EmbedApps, EmbedPods, EmbedGroups)

  /**
    * Path matchers. Needed since Jersey is not able to handle parameters with slashes.
    */
  val ListApps: Regex = """^((?:.+/)|)apps$""".r
  val ListRootApps: Regex = """^apps$""".r
  val ListVersionsRE: Regex = """^(.+)/versions$""".r
  val ListRootVersionRE: Regex = """^versions$""".r
  val GetVersionRE: Regex = """^(.+)/versions/(.+)$""".r
  val GetRootVersionRE: Regex = """^versions/(.+)$""".r

  /**
    * Get root group.
    */
  @GET
  def root(@Context req: HttpServletRequest, @QueryParam("embed") embed: java.util.Set[String]): Response =
    group("/", embed, req)

  /**
    * Get a specific group, optionally with specific version
    * @param id the identifier of the group encoded as path
    * @return the group or the group versions.
    */
  @GET
  @Path("""{id:.+}""")
  def group(
    @PathParam("id") id: String,
    @QueryParam("embed") embed: java.util.Set[String],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    import mesosphere.marathon.core.async.ExecutionContexts.global

    val embeds: Set[String] = if (embed.isEmpty) defaultEmbeds else embed
    val (appEmbed, groupEmbed) = resolveAppGroup(embeds)

    //format:off
    def appsResponse(id: PathId) =
      infoService.selectAppsInGroup(id, authorizationSelectors.appSelector, appEmbed).map(info => ok(info))

    def groupResponse(id: PathId) =
      infoService.selectGroup(id, authorizationSelectors, appEmbed, groupEmbed).map {
        case Some(info) => ok(info)
        case None if id.isRoot => ok(GroupInfo.empty)
        case None => unknownGroup(id)
      }

    def groupVersionResponse(id: PathId, version: Timestamp) =
      infoService.selectGroupVersion(id, version, authorizationSelectors, groupEmbed).map {
        case Some(info) => ok(info)
        case None => unknownGroup(id)
      }

    def versionsResponse(groupId: PathId) = {
      withAuthorization(ViewGroup, groupManager.group(groupId), unknownGroup(groupId)) { _ =>
        result(groupManager.versions(groupId).runWith(Sink.seq).map(versions => ok(versions)))
      }
    }

    val response: Future[Response] = id match {
      case ListApps(gid) => appsResponse(gid.toRootPath)
      case ListRootApps() => appsResponse(PathId.empty)
      case ListVersionsRE(gid) => Future.successful(versionsResponse(gid.toRootPath))
      case ListRootVersionRE() => Future.successful(versionsResponse(PathId.empty))
      case GetVersionRE(gid, version) => groupVersionResponse(gid.toRootPath, Timestamp(version))
      case GetRootVersionRE(version) => groupVersionResponse(PathId.empty, Timestamp(version))
      case _ => groupResponse(id.toRootPath)
    }

    result(response)
  }

  /**
    * Create a new group.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @param body the request body as array byte buffer
    */
  @POST
  def create(
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = createWithPath("", force, body, req)

  /**
    * performs basic app validation and normalization for all apps (transitively) for the given group-update.
    */
  def normalizeApps(basePath: PathId, update: raml.GroupUpdate): raml.GroupUpdate = {
    // note: we take special care to:
    // (a) canonize and rewrite the app ID before normalization, and;
    // (b) canonize BUT NOT REWRITE the group ID while iterating (validation has special rules re: number of set fields)

    // convert apps to canonical form here
    val apps = update.apps.map(_.map { a =>
      a.copy(id = a.id.toPath.canonicalPath(basePath).toString).normalize
    })

    val groups = update.groups.map(_.map { g =>
      // TODO: this "getOrElse" logic seems funny, but it's what nested group validation does;
      // funny because all child groups that contain apps should probably specify an ID -- default to the parent's
      // base seems wrong.
      val groupBase = g.id.map(_.toPath.canonicalPath(basePath)).getOrElse(basePath)

      // TODO: recursion without tailrec
      normalizeApps(groupBase, g)
    })

    update.copy(apps = apps, groups = groups)
  }

  /**
    * Create a group.
    * If the path to the group does not exist, it gets created.
    * @param id is the identifier of the the group to update.
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @param body the request body as array byte buffer
    */
  @POST
  @Path("""{id:.+}""")
  def createWithPath(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    assumeValid {
      val validatedId = validateOrThrow(id.toRootPath)
      val raw = Json.parse(body).as[raml.GroupUpdate]
      val effectivePath = raw.id.map(id => validateOrThrow(PathId(id)).canonicalPath(validatedId)).getOrElse(validatedId)

      val groupValidator = Group.validNestedGroupUpdateWithBase(effectivePath)
      val groupUpdate = validateOrThrow(
        normalizeApps(
          effectivePath,
          raw
        ))(groupValidator)

      val rootGroup = groupManager.rootGroup()

      def throwIfConflicting[A](conflict: Option[Any], msg: String) = {
        conflict.map(_ => throw ConflictingChangeException(msg))
      }

      throwIfConflicting(
        rootGroup.group(effectivePath),
        s"Group $effectivePath is already created. Use PUT to change this group.")

      throwIfConflicting(
        rootGroup.transitiveAppsById.get(effectivePath),
        s"An app with the path $effectivePath already exists.")

      val (deployment, path) = updateOrCreate(effectivePath, groupUpdate, force)
      deploymentResult(deployment, Response.created(new URI(path.toString)))
    }
  }

  @PUT
  def updateRoot(
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @DefaultValue("false")@QueryParam("dryRun") dryRun: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = {
    update("", force, dryRun, body, req)
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
  def update(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @DefaultValue("false")@QueryParam("dryRun") dryRun: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    assumeValid {
      val validatedId = validateOrThrow(id.toRootPath)
      val raw = Json.parse(body).as[raml.GroupUpdate]
      val effectivePath = raw.id.map(id => validateOrThrow(PathId(id)).canonicalPath(validatedId)).getOrElse(validatedId)

      val groupValidator = Group.validNestedGroupUpdateWithBase(effectivePath)
      val groupUpdate = validateOrThrow(
        normalizeApps(
          effectivePath,
          raw
        ))(groupValidator)

      val newVersion = Timestamp.now()

      if (dryRun) {

        val originalGroup = groupManager.rootGroup()
        val updatedGroup = applyGroupUpdate(originalGroup, effectivePath, groupUpdate, newVersion)

        ok(
          Json.obj(
            "steps". ->(DeploymentPlan(originalGroup, updatedGroup).steps)
          ).toString()
        )
      } else {
        val (deployment, _) = updateOrCreate(effectivePath, groupUpdate, force)
        deploymentResult(deployment)
      }
    }
  }

  @DELETE
  def delete(
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val version = Timestamp.now()

    def clearRootGroup(rootGroup: RootGroup): RootGroup = {
      checkAuthorization(DeleteGroup, rootGroup)
      RootGroup(version = version)
    }

    val deployment = result(groupManager.updateRoot(PathId.empty, clearRootGroup, version, force))
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
  def delete(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val groupId = id.toRootPath
    val version = Timestamp.now()

    def deleteGroup(rootGroup: RootGroup) = {
      rootGroup.group(groupId) match {
        case Some(group) => checkAuthorization(DeleteGroup, group)
        case None => throw UnknownGroupException(groupId)
      }
      rootGroup.removeGroup(groupId, version)
    }

    val deployment = result(groupManager.updateRoot(groupId.parent, deleteGroup, version, force))
    deploymentResult(deployment)
  }

  private def applyGroupUpdate(
    rootGroup: RootGroup,
    groupId: PathId,
    groupUpdate: raml.GroupUpdate,
    newVersion: Timestamp)(implicit identity: Identity): RootGroup = {
    val group = rootGroup.group(groupId).getOrElse(Group.empty(groupId))

    def versionChange: Option[RootGroup] = groupUpdate.version.map { version =>
      val targetVersion = Timestamp(version)
      checkAuthorization(UpdateGroup, group)
      val versionedGroup = result(groupManager.group(group.id, targetVersion))
        .map(checkAuthorization(ViewGroup, _))
      rootGroup.putGroup(versionedGroup.getOrElse(
        throw new IllegalArgumentException(s"Group $group.id not available in version $targetVersion")
      ), newVersion)
    }

    def scaleChange: Option[RootGroup] = groupUpdate.scaleBy.map { scale =>
      checkAuthorization(UpdateGroup, group)
      rootGroup.updateTransitiveApps(group.id, app => app.copy(instances = (app.instances * scale).ceil.toInt), newVersion)
    }

    def createOrUpdateChange: RootGroup = {
      // groupManager.update always passes a group, even if it doesn't exist
      val maybeExistingGroup = groupManager.group(group.id)
      val appConversionFunc: (raml.App => AppDefinition) = Raml.fromRaml[raml.App, AppDefinition]
      val updatedGroup: Group = Raml.fromRaml(
        GroupConversion(groupUpdate, group, newVersion) -> appConversionFunc)

      maybeExistingGroup.fold(checkAuthorization(CreateRunSpec, updatedGroup))(checkAuthorization(UpdateGroup, _))

      rootGroup.putGroup(updatedGroup, newVersion)
    }

    versionChange.orElse(scaleChange).getOrElse(createOrUpdateChange)
  }

  private def updateOrCreate(
    id: PathId,
    update: raml.GroupUpdate,
    force: Boolean)(implicit identity: Identity): (DeploymentPlan, PathId) = {
    val version = Timestamp.now()

    val effectivePath = update.id.map(PathId(_).canonicalPath(id)).getOrElse(id)
    val deployment = result(groupManager.updateRoot(id.parent, applyGroupUpdate(_, effectivePath, update, version), version, force))
    (deployment, effectivePath)
  }

  def authorizationSelectors(implicit identity: Identity): GroupInfoService.Selectors = {
    GroupInfoService.Selectors(
      AppsResource.authzSelector,
      PodsResource.authzSelector,
      authzSelector)
  }
}

object GroupsResource {

  private def authzSelector(implicit authz: Authorizer, identity: Identity) = Selector[Group] { g =>
    authz.isAuthorized(identity, ViewGroup, g)
  }
}
