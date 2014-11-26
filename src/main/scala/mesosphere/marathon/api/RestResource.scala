package mesosphere.marathon.api

import java.net.URI
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.{ ResponseBuilder, Status }
import scala.concurrent.{ Await, Awaitable }

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan

trait RestResource {

  val config: MarathonConf

  protected def unknownGroup(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"Group '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  protected def unknownTask(id: String): Response = notFound(s"Task '$id' does not exist")

  protected def unknownApp(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"App '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  protected def notFound(message: String): Response = {
    Response.status(Status.NOT_FOUND).entity(Map("message" -> message)).build
  }

  protected def deploymentResult(d: DeploymentPlan, response: ResponseBuilder = Response.ok()) = {
    response.entity(Map("version" -> d.version, "deploymentId" -> d.id)).build()
  }

  protected def status(code: Status) = Response.status(code).build()
  protected def status(code: Status, entity: AnyRef) = Response.status(code).entity(entity).build()
  protected def ok(): Response = Response.ok().build()
  protected def ok(entity: Any): Response = Response.ok(entity).build()
  protected def created(uri: String): Response = Response.created(new URI(uri)).build()
  protected def noContent: Response = Response.noContent().build()

  protected def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)
}
