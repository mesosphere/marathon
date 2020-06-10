package mesosphere.marathon
package api

import java.io.{BufferedOutputStream, OutputStream}
import java.net.URI

import javax.ws.rs.container.AsyncResponse
import javax.ws.rs.core.{MediaType, Response, StreamingOutput}
import javax.ws.rs.core.Response.{ResponseBuilder, Status}
import akka.http.scaladsl.model.StatusCodes
import com.wix.accord.{Validator, Failure => ValidationFailure, Success => ValidationSuccess}
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.raml.RamlSerializer
import mesosphere.marathon.state.{PathId, Timestamp}
import org.apache.commons.io.output.ByteArrayOutputStream
import play.api.libs.json.JsonValidationError
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait RestResource extends JaxResource {
  import RestResource.RestStreamingBody
  implicit val executionContext: ExecutionContext
  case class FailureResponse(response: Response) extends Throwable

  /**
    * Used for async controller methods, propagates the result of the returned Future to the asyncResponse.
    *
    * Any synchronous exceptions thrown inside of the block are also propagated.
    *
    * @param asyncResponse The AsyncResponse instance for the request to a controller method
    * @param body The response-generating code.
    */
  def sendResponse(asyncResponse: AsyncResponse)(body: => Future[Response])(implicit ec: ExecutionContext) =
    try {
      body.onComplete {
        case Success(r) =>
          asyncResponse.resume(r: Object)
        case Failure(f: Throwable) =>
          asyncResponse.resume(f)
      }(ec)
    } catch {
      case ex: Throwable =>
        asyncResponse.resume(ex)

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
    Response.status(Status.NOT_FOUND).entity(new RestStreamingBody(raml.Error(message))).build()
  }

  protected def deploymentResult(d: DeploymentPlan, response: ResponseBuilder = Response.ok()) = {
    response
      .entity(new RestStreamingBody(raml.DeploymentResult(version = d.version.toOffsetDateTime, deploymentId = d.id)))
      .header(RestResource.DeploymentHeader, d.id)
      .build()
  }

  protected def status(code: Status) = Response.status(code).build()
  protected def status(code: Status, entity: String) = Response.status(code).entity(new RestStreamingBody(entity)).build()
  protected def ok(): Response = Response.ok().build()
  protected def ok(entity: String): Response = Response.ok(entity).build()
  protected def ok(entity: String, mediaType: MediaType): Response = Response.ok(entity).`type`(mediaType).build()
  protected def ok[T <: raml.RamlGenerated](obj: Seq[T]): Response = Response.ok(new RestStreamingBody(obj)).build()
  protected def ok[T <: raml.RamlGenerated](obj: T): Response = Response.ok(new RestStreamingBody(obj)).build()
  protected def ok[T <: raml.RamlGenerated](obj: T, mediaType: MediaType): Response =
    Response.ok(new RestStreamingBody(obj)).`type`(mediaType).build()
  protected def created(uri: String): Response = Response.created(new URI(uri)).build()
  protected def noContent: Response = Response.noContent().build()

  // TODO: Remove when all APi models are in RAML.
  protected def jsonString[T](obj: T)(implicit writes: Writes[T]): String = Json.stringify(Json.toJson(obj))

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

  protected def withValidF[T](t: T)(fn: T => Future[Response])(implicit validator: Validator[T]): Future[Response] = {
    // TODO - replace with Validation.validateOrThrow
    validator(t) match {
      case f: ValidationFailure =>
        val entity = Json.toJson(f).toString
        Future.successful(Response.status(StatusCodes.UnprocessableEntity.intValue).entity(entity).build())
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

  class RestStreamingBody[T](body: T) extends StreamingOutput {
    override def write(output: OutputStream): Unit = {
      val writer = new BufferedOutputStream(output)
      RamlSerializer.serializer.writeValue(writer, body)
      writer.flush()
    }

    override def toString: String = {
      val out = new ByteArrayOutputStream()
      write(out)
      out.toString("UTF-8")
    }
  }
}
