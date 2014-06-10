package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.{ Request, Response, MediaType }
import javax.inject.Inject
import javax.validation.{ ConstraintViolation, ConstraintViolationException, Validation }
import mesosphere.marathon.state.{ GroupId, Group, Timestamp, GroupManager }
import scala.concurrent.Await.result
import scala.concurrent.duration._
import mesosphere.marathon.api.Responses
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import org.hibernate.validator.internal.engine.ConstraintViolationImpl
import java.lang.annotation.ElementType
import org.hibernate.validator.internal.engine.path.PathImpl
import scala.reflect.ClassTag
import scala.collection.mutable

@Path("v2/groups")
@Produces(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject() (groupManager: GroupManager) {

  val defaultWait = 3.seconds
  val ListVersionsRE = """^(.+)/versions$""".r
  val GetVersionRE = """^(.+)/versions/(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)$""".r

  @GET
  def list(): Iterable[Group] = result(groupManager.list(), defaultWait)

  @GET
  @Path("""{path:.+}""")
  def group(@PathParam("path") path: String): Response = {
    def groupResponse(g: Option[Group]) = g match {
      case Some(group) => Response.ok(group).build()
      case None        => Responses.unknownGroup(path)
    }
    path match {
      case ListVersionsRE(id)        => Response.ok(result(groupManager.versions(id), defaultWait)).build()
      case GetVersionRE(id, version) => groupResponse(result(groupManager.group(id, Timestamp(version)), defaultWait))
      case _                         => groupResponse(result(groupManager.group(path), defaultWait))
    }
  }

  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def create(update: GroupUpdate): Response = {
    updateOrCreate(update.id.get, update, force = false)
  }

  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("""{path:.+}""")
  def createUpdate(@PathParam("path") path: String,
                   update: GroupUpdate,
                   @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    updateOrCreate(path, update, force)
  }

  @PUT
  @Consumes(Array(MediaType.APPLICATION_JSON)) //@Path("""{path:(?!.*/version/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$).+}""")
  @Path("""{path:.+}""")
  def update(@PathParam("path") path: String,
             update: GroupUpdate,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    updateOrCreate(path, update, force)
  }

  def updateOrCreate(id: GroupId, update: GroupUpdate, force: Boolean): Response = {
    checkIsValid(update)
    val version = Timestamp.now()
    groupManager.update(id, version, group => update.apply(group, version), force)
    Response.ok(Map("version" -> version)).build()
  }

  //TODO: filter concurrent paths, seems to be a problem with jaxrs
  /*
  @PUT
  @Path("""{id}/version/{version}""")
  def rollbackTo(@PathParam("id") id: String,
                 @PathParam("version") version: String,
                 @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val res = groupManager.group(id, Timestamp(version)).map {
      case Some(group) =>
        groupManager.update(id, group, force)
        Response.noContent().build()
      case None =>
        Responses.unknownGroup(id)
    }
    result(res, defaultWait)
  }
  */

  @DELETE
  @Path("""{id:.+}""")
  def delete(@PathParam("id") id: String,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val gid = GroupId(id)
    if (gid.isRoot) {
      val response = if (result(groupManager.expunge(id), defaultWait)) Response.ok else Response.noContent()
      response.build()
    }
    else {
      val version = Timestamp.now()
      groupManager.update(gid.root, version, _.remove(gid, version), force)
      Response.ok(Map("version" -> version)).build()
    }
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
      val appErrors = group.apps.flatMap(app => validator.validate(app).asScala).zipWithIndex.map(a => withPath(root, a._1, path + s"apps[${a._2}]."))
      val nestedGroupErrors = group.groups.zipWithIndex.flatMap(g => groupValidation(path + s"groups[${g._2}].", g._1))
      val healthCapacityNotInRange =
        if (group.scalingStrategy.minimumHealthCapacity < 0) Some("is less than 0")
        else if (group.scalingStrategy.minimumHealthCapacity > 1) Some("is greater than 1")
        else None

      val runningMinimumExceeded = group.scalingStrategy.maximumRunningFactor.collect {
        case x if x < 1 => "is less than 1"
        case x if x <= group.scalingStrategy.minimumHealthCapacity => "is less than or equal to minimumHealthCapacity"
      }

      val scalingErrors = healthCapacityNotInRange map { msg =>
        ConstraintViolationImpl.forParameterValidation[GroupUpdate](
          msg, msg, classOf[GroupUpdate], group, group.scalingStrategy, group.scalingStrategy,
          PathImpl.createPathFromString(path + "scalingStrategy.minimumHealthCapacity"),
          null, ElementType.FIELD, Array())
      }

      val capacityErrors = runningMinimumExceeded.toList map { msg =>
        ConstraintViolationImpl.forParameterValidation[GroupUpdate](
          msg, msg, classOf[GroupUpdate], group, group.scalingStrategy, group.scalingStrategy,
          PathImpl.createPathFromString(path + "scalingStrategy.maximumRunningFactor"),
          null, ElementType.FIELD, Array())
      }
      groupErrors ++ nestedGroupErrors ++ appErrors ++ scalingErrors ++ capacityErrors
    }

    val errors = groupValidation("", root)
    if (!errors.isEmpty) throw new ConstraintViolationException("Group is not valid", errors.asJava)
  }
}
