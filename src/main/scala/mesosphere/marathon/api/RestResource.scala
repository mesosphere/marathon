package mesosphere.marathon
package api

import java.net.URI
import javax.ws.rs.container.AsyncResponse
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.{ResponseBuilder, Status}

import akka.http.scaladsl.model.StatusCodes
import com.wix.accord.{Failure => ValidationFailure, Validator, Success => ValidationSuccess}
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state.{PathId, Timestamp}
import play.api.libs.json.JsonValidationError
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import scala.concurrent.{Await, Awaitable, ExecutionContext, Future}
import scala.util.{Failure, Success}

trait RestResource extends JaxResource {
  implicit val executionContext: ExecutionContext
  protected val config: MarathonConf
  case class FailureResponse(response: Response) extends Throwable

  def sendResponse(asyncResponse: AsyncResponse)(future: Future[Response]) = {
    future.onComplete {
      case Success(r) =>
        asyncResponse.resume(r: Object)
      case Failure(f: Throwable) =>
        asyncResponse.resume(f: Throwable)
    }
  }
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
    * See [[validateOrThrow]], which is preferred to this.
    *
    * @param t object to validate
    * @param fn function to execute after successful validation
    * @param validator validator to use
    * @tparam T type of object
    * @return returns a 422 response if there is a failure due to validation. Executes fn function if successful.
    */
  protected def withValid[T](t: T)(fn: T => Response)(implicit validator: Validator[T]): Response = {
    // TODO - replace with Validation.validateOrThrow
    validator(t) match {
      case f: ValidationFailure =>
        val entity = Json.toJson(f).toString
        Response.status(StatusCodes.UnprocessableEntity.intValue).entity(entity).build()
      case ValidationSuccess => fn(t)
    }
  }
}

object RestResource {

  /**
    * By default, if an endpoint accepts both text/plain and application/json, when Accept: * / * is provided then the
    * server will default to text/plain. This media type will cause text/plain to be a lower priority, so other
    * mediatypes will be preferred by default.
    */
  final val TEXT_PLAIN_LOW = "text/plain;qs=0.1"

  val DeploymentHeader = "Marathon-Deployment-Id"

  def entity(err: scala.collection.Seq[(JsPath, scala.collection.Seq[JsonValidationError])]): JsValue = {
    val errors = err.map {
      case (path, errs) => Json.obj("path" -> path.toString(), "errors" -> errs.map(_.message).distinct)
    }
    Json.obj(
      "message" -> "Invalid JSON",
      "details" -> errors
    )
  }
}
