package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import javax.validation.{ConstraintViolationException, Validation, Valid}
import mesosphere.marathon.state.{Timestamp, GroupManager}
import scala.concurrent.Await.result
import scala.concurrent.duration._
import mesosphere.marathon.api.{Responses, PATCH}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import org.hibernate.validator.internal.engine.{MessageInterpolatorContext, ConstraintViolationImpl}
import java.lang.annotation.ElementType
import org.hibernate.validator.internal.engine.path.{NodeImpl, PathImpl}

@Path("v2/groups")
@Produces(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject()(groupManager: GroupManager) {

  val defaultWait = 3.seconds

  @GET
  def list() : Iterable[Group] = result(groupManager.list(), defaultWait)

  @GET
  @Path("{id}")
  def group(@PathParam("id") id: String) : Option[Group] = result(groupManager.group(id), defaultWait)

  @GET
  @Path("{id}/versions")
  def listVersions(@PathParam("id") id: String) : Iterable[Timestamp] = {
    result(groupManager.versions(id), defaultWait)
  }

  @GET
  @Path("{id}/versions/{version}")
  def groupVersion(@PathParam("id") id: String, @PathParam("version") version: String) : Option[Group] = {
    result(groupManager.group(id, Timestamp(version)), defaultWait)
  }

  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def create( group: Group ) : Response = {
    checkIsValid(group)
    groupManager.create(group)
    Response.noContent().build()
  }

  @PUT
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  def update( @PathParam("id") id: String,
              group: Group,
              @DefaultValue("false") @QueryParam("force") force:Boolean) : Response = {
    checkIsValid(group)
    groupManager.update(id, group, force)
    Response.noContent().build()
  }

  @PUT
  @Path("{id}/version/{version}")
  def rollbackTo( @PathParam("id") id: String,
                  @PathParam("version") version: String,
                  @DefaultValue("false") @QueryParam("force") force:Boolean) : Response = {
    val res = groupManager.group(id, Timestamp(version)).map {
      case Some(group) =>
        groupManager.update(id, group, force)
        Response.noContent().build()
      case None =>
        Responses.unknownGroup(id)
    }
    result(res, defaultWait)
  }

  /* patch support is postponed
  @PATCH
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  def patch( @PathParam("id") id:String,
             update: GroupUpdate,
             @DefaultValue("false") @QueryParam("force") force:Boolean) : Response = {
    groupManager.update(id, update.apply _, force)
    Response.noContent().build()
  }
  */

  @DELETE
  @Path("{id}")
  def delete( @PathParam("id") id: String ) : Response = {
    val response = if (result(groupManager.expunge(id), defaultWait)) Response.ok else Response.noContent()
    response.build()
  }

  //Note: this is really ugly. It is necessary, since bean validation will not walk into a scala Seq[_] and
  //can not check scala Double values. So we have to do this by hand.
  val validator = Validation.buildDefaultValidatorFactory().getValidator
  private def checkIsValid(group:Group) {
    val groupErrors = validator.validate(group).asScala
    val appErrors = group.apps.flatMap( app => validator.validate(app).asScala).map { e =>
      ConstraintViolationImpl.forParameterValidation[Group](
        e.getMessageTemplate, e.getMessage, classOf[Group], group, e.getLeafBean, e.getInvalidValue,
        PathImpl.createPathFromString("apps."+e.getPropertyPath),
        e.getConstraintDescriptor, ElementType.FIELD, e.getExecutableParameters)
    }
    val min = if (group.scalingStrategy.minimumHealthCapacity<0) Some("is less than 0") else None
    val max = if (group.scalingStrategy.minimumHealthCapacity>1) Some("is greater than 1") else None
    val scalingErrors = min orElse max map { msg =>
      ConstraintViolationImpl.forParameterValidation[Group](
        msg, msg, classOf[Group], group, group.scalingStrategy, group.scalingStrategy,
        PathImpl.createPathFromString("scalingStrategy.minimumHealthCapacity"),
        null, ElementType.FIELD, Array())
    }
    val errors = groupErrors ++ appErrors ++ scalingErrors
    if (!errors.isEmpty) throw new ConstraintViolationException("Group is not valid", errors.toSet.asJava)
  }
}
