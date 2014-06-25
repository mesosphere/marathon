package mesosphere.marathon.api.v2

import java.lang.annotation.ElementType
import java.net.URI
import javax.inject.Inject
import javax.validation.{ ConstraintViolation, ConstraintViolationException, Validation }
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.Responses
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Group, GroupManager, PathId, Timestamp }
import mesosphere.util.ThreadPoolContext.context
import org.hibernate.validator.internal.engine.ConstraintViolationImpl
import org.hibernate.validator.internal.engine.path.PathImpl

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ Await, Awaitable }
import scala.reflect.ClassTag

@Path("v2/groups")
@Produces(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject() (groupManager: GroupManager, config: MarathonConf) {

  val ListVersionsRE = """^(.+)/versions$""".r
  val GetVersionRE = """^(.+)/versions/(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)$""".r

  /**
    * Get root group.
    */
  @GET
  def root(): Group = result(groupManager.root)

  /**
    * Get a specific group, optionally with specific version
    * @param id the identifier of the group encoded as path
    * @return the group or the group versions.
    */
  @GET
  @Path("""{id:.+}""")
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
  def create(update: GroupUpdate): Response = {
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
  def createUpdate(@PathParam("id") id: String,
                   update: GroupUpdate,
                   @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val (path, version) = updateOrCreate(id.toRootPath, update, force)
    Response.created(new URI(path.toString)).entity(Map("version" -> version)).build()
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
  def update(@PathParam("id") id: String,
             update: GroupUpdate,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    updateOrCreate(id.toRootPath, update, force)
    val (_, version) = updateOrCreate(id.toRootPath, update, force)
    Response.ok(Map("version" -> version)).build()
  }

  /**
    * Rollback to a specific version of a given group.
    * @param id the identifier of the group to roll back.
    * @param version the version of the group to roll to.
    * @param force if there is an upgrade in progress, it can be overridden with the force flag.
    */
  @PUT
  @Path("""{id:.+}/version/{version}""")
  def rollbackTo(@PathParam("id") id: String,
                 @PathParam("version") version: String,
                 @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val groupId = id.toRootPath
    val res = groupManager.group(groupId, Timestamp(version)).map {
      case Some(group) =>
        groupManager.update(groupId, _ => group, group.version, force)
        Response.ok(group).build()
      case None =>
        Responses.unknownGroup(groupId)
    }
    result(res)
  }

  /**
    * Delete a specific subtree or a complete tree.
    * @param id the identifier of the group to delete encoded as path
    * @param force if the change has to be forced. A running upgrade process will be halted and the new one is started.
    * @return A version response, which defines the resulting change.
    */
  @DELETE
  @Path("""{id:.+}""")
  def delete(@PathParam("id") id: String,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val groupId = id.toRootPath
    val version = Timestamp.now()
    groupManager.update(groupId.parent, _.remove(groupId, version), version, force)
    Response.ok(Map("version" -> version)).build()
  }

  private def updateOrCreate(id: PathId, update: GroupUpdate, force: Boolean): (PathId, Timestamp) = {
    checkIsValid(update)
    val version = Timestamp.now()
    val effectivePath = update.id.map(_.canonicalPath(id)).getOrElse(id)
    groupManager.update(effectivePath, group => update.apply(group, version), version, force)
    (effectivePath, version)
  }

  //Note: this is really ugly. It is necessary, since bean validation will not walk into a scala Seq[_] and
  //can not check scala Double values. So we have to do this by hand.
  val validator = Validation.buildDefaultValidatorFactory().getValidator
  private def checkIsValid(root: GroupUpdate) {
    def withPath[T](bean: T, e: ConstraintViolation[_], path: String)(implicit ct: ClassTag[T]): ConstraintViolation[T] = {
      ConstraintViolationImpl.forParameterValidation[T](
        e.getMessageTemplate, e.getMessage, ct.runtimeClass.asInstanceOf[Class[T]], bean, e.getLeafBean, e.getInvalidValue,
        PathImpl.createPathFromString(path + e.getPropertyPath),
        e.getConstraintDescriptor, ElementType.FIELD, e.getExecutableParameters)
    }
    def groupValidation(path: String, group: GroupUpdate): mutable.Set[ConstraintViolation[GroupUpdate]] = {
      val groupErrors = validator.validate(group).asScala.map(withPath(root, _, path))
      val appErrors = group.apps
        .getOrElse(Seq.empty)
        .flatMap(app => validator.validate(app).asScala)
        .zipWithIndex
        .map(a => withPath(root, a._1, path + s"apps[${a._2}]."))
      val nestedGroupErrors = group.groups
        .getOrElse(Seq.empty)
        .zipWithIndex
        .flatMap(g => groupValidation(path + s"groups[${g._2}].", g._1))
      groupErrors ++ nestedGroupErrors ++ appErrors
    }

    val errors = groupValidation("", root)
    if (errors.nonEmpty) throw new ConstraintViolationException("Group is not valid", errors.asJava)
  }

  private def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)
}
