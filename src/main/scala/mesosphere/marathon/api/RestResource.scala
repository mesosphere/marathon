package mesosphere.marathon
package api

import java.net.URI
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.{ ResponseBuilder, Status }

import akka.http.scaladsl.model.StatusCodes
import com.wix.accord._
import mesosphere.marathon.ValidationFailedException
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.core.deployment.DeploymentPlan
import play.api.data.validation.ValidationError
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._

import scala.concurrent.{ Await, Awaitable }

trait RestResource {

  protected val config: MarathonConf

  protected def unknownGroup(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"Group '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  protected def unknownTask(id: String): Response = notFound(s"Task '$id' does not exist")

  protected def unknownApp(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"App '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  protected def unknownPod(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"Pod '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  protected def notFound(message: String): Response = {
    Response.status(Status.NOT_FOUND).entity(jsonObjString("message" -> message)).build()
  }

  protected def deploymentResult(d: DeploymentPlan, response: ResponseBuilder = Response.ok()) = {
    response.entity(jsonObjString("version" -> d.version, "deploymentId" -> d.id))
      .header(RestResource.DeploymentHeader, d.id)
      .build()
  }

  protected def status(code: Status) = Response.status(code).build()
  protected def status(code: Status, entity: AnyRef) = Response.status(code).entity(entity).build()
  protected def ok(): Response = Response.ok().build()
  protected def ok(entity: String): Response = Response.ok(entity).build()
  protected def ok[T](obj: T)(implicit writes: Writes[T]): Response = ok(jsonString(obj))
  protected def created(uri: String): Response = Response.created(new URI(uri)).build()
  protected def noContent: Response = Response.noContent().build()

  protected def jsonString[T](obj: T)(implicit writes: Writes[T]): String = Json.stringify(Json.toJson(obj))
  protected def jsonObjString(fields: (String, JsValueWrapper)*): String = Json.stringify(Json.obj(fields: _*))
  protected def jsonArrString(fields: JsValueWrapper*): String = Json.stringify(Json.arr(fields: _*))

  protected def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)

  /**
    * Checks if the implicit validator yields a valid result.
    * See [[assumeValid]], which is preferred to this.
    *
    * @param t object to validate
    * @param description optional description which might be injected into the failure message
    * @param fn function to execute after successful validation
    * @param validator validator to use
    * @tparam T type of object
    * @return returns a 422 response if there is a failure due to validation. Executes fn function if successful.
    */
  protected def withValid[T](t: T, description: Option[String] = None)(fn: T => Response)(implicit validator: Validator[T]): Response = {
    validator(t) match {
      case f: Failure =>
        val entity = Json.toJson(description.map(f.withDescription).getOrElse(f)).toString
        Response.status(StatusCodes.UnprocessableEntity.intValue).entity(entity).build()
      case Success => fn(t)
    }
  }

  /**
    * Execute the given function and if any validation errors crop up, generate an UnprocessableEntity
    * HTTP status code and send the validation error as the response body (in JSON form).
    * @param f
    * @return
    */
  protected def assumeValid(f: => Response): Response =
    try {
      f
    } catch {
      case vfe: ValidationFailedException =>
        // model validation generates these errors
        val entity = Json.toJson(vfe.failure).toString
        Response.status(StatusCodes.UnprocessableEntity.intValue).entity(entity).build()

      case JsResultException(errors) if errors.nonEmpty && errors.forall {
        case (_, validationErrors) => validationErrors.nonEmpty
      } =>
        // Javascript validation generates these errors
        // if all of the nested errors are validation-related then generate
        // an error code consistent with that generated for ValidationFailedException
        val entity = RestResource.entity(errors).toString
        Response.status(StatusCodes.UnprocessableEntity.intValue).entity(entity).build()
    }
}

object RestResource {
  val DeploymentHeader = "Marathon-Deployment-Id"

  def entity(err: scala.collection.Seq[(JsPath, scala.collection.Seq[ValidationError])]): JsValue = {
    val errors = err.map {
      case (path, errs) => Json.obj("path" -> path.toString(), "errors" -> errs.map(_.message).distinct)
    }
    Json.obj(
      "message" -> "Invalid JSON",
      "details" -> errors
    )
  }
}
