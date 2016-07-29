package mesosphere.marathon.api

import java.net.URI
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.{ ResponseBuilder, Status }

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{ Writes, Json }

import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._

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

  protected def notFound(message: String): Response = {
    Response.status(Status.NOT_FOUND).entity(jsonObjString("message" -> message)).build()
  }

  protected def deploymentResult(d: DeploymentPlan, response: ResponseBuilder = Response.ok()) = {
    response.entity(jsonObjString("version" -> d.version, "deploymentId" -> d.id)).build()
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

  //scalastyle:off
  /**
    * Checks if the implicit validator yields a valid result.
    * @param t object to validate
    * @param description optional description which might be injected into the failure message
    * @param fn function to execute after successful validation
    * @param validator validator to use
    * @tparam T type of object
    * @return returns a 422 response if there is a failure due to validation. Executes fn function if successful.
    */
  protected def withValid[T](t: T, description: Option[String] = None)(fn: T => Response)(implicit validator: Validator[T]): Response = {
    //scalastyle:on
    validator(t) match {
      case f: Failure =>
        val entity = Json.toJson(description.map(f.withDescription).getOrElse(f)).toString
        //scalastyle:off magic.number
        Response.status(422).entity(entity).build()
      //scalastyle:on
      case Success => fn(t)
    }
  }
}
