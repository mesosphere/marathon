package mesosphere.marathon
package api.v3

import java.net.URI

import akka.{Done, NotUsed}
import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api.v2.{AppHelpers, AppNormalization}
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{AuthResource, RestResource}

import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state._
import org.glassfish.jersey.server.ManagedAsync
import play.api.libs.json.{JsString, Json}

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

@Path("v3/templates")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class TemplatesResource @Inject() (
    templateRepository: TemplateRepository,
    eventBus: EventStream,
    val config: MarathonConf,
    pluginManager: PluginManager)(implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val executionContext: ExecutionContext,
    val mat: Materializer) extends RestResource with AuthResource {

  import AppHelpers._
  import Normalization._

  private[this] val ListApps = """^((?:.+/)|)\*$""".r
  private implicit lazy val appDefinitionValidator = AppDefinition.validAppDefinition(config.availableFeatures)(pluginManager)

  private val normalizationConfig = AppNormalization.Configuration(
    config.defaultNetworkName.toOption,
    config.mesosBridgeName())

  private implicit val validateAndNormalizeApp: Normalization[raml.App] =
    appNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())

  @SuppressWarnings(Array("all")) /* async/await */
  @POST
  @ManagedAsync
  def create(
    body: Array[Byte],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val rawApp = Raml.fromRaml(Json.parse(body).as[raml.App].normalize)

      val app = validateOrThrow(rawApp)

      checkAuthorization(CreateRunSpec, rawApp)

      val version = await(templateRepository.create(app))

      // servletRequest.getAsyncContext
      Response
        .created(new URI(app.id.toString))
        .entity(jsonObjString("version" -> JsString(version.toString)))
        .build()
    }
  }

  @GET
  @Path("""{id:.+}/latest""")
  def findLatestFullPath(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = {
    findLatest(id, req, asyncResponse)
  }

  @GET
  @Path("""{id:.+}""")
  def findLatest(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val template = result(templateRepository.read(PathId(id), None))

      checkAuthorization(ViewRunSpec, template)

      Response
        .ok()
        .entity(jsonObjString("template" -> template))
        .build()
    }
  }

  @GET
  @Path("{id:.+}/versions")
  def listVersions(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val template = result(templateRepository.read(PathId(id), None))

      checkAuthorization(ViewRunSpec, template)

      val versions = result(templateRepository.versions(PathId(id)).runWith(Sink.seq))

      Response
        .ok()
        .entity(jsonObjString("versions" -> versions))
        .build()

    }
  }

  @GET
  @Path("{id:.+}/versions/{version}")
  def findVersion(
    @PathParam("id") id: String,
    @PathParam("version") version: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val template = await(templateRepository.read(PathId(id), Some(version)))

      checkAuthorization(ViewRunSpec, template)

      Response
        .ok()
        .entity(jsonObjString("template" -> template))
        .build()
    }
  }

  @SuppressWarnings(Array("all")) /* async/await */
  @DELETE
  @Path("""{id:.+}""")
  def delete(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val templateId = PathId(id)

      val template = await(templateRepository.read(templateId, None))

      checkAuthorization(DeleteRunSpec, Some(template), TemplateNotFoundException(templateId))

      await(templateRepository.delete(PathId(id)))

      Response
        .ok()
        .build()
    }
  }

  @SuppressWarnings(Array("all")) /* async/await */
  @DELETE
  @Path("{id:.+}/versions/{version}")
  def delete(
    @PathParam("id") id: String,
    @PathParam("version") version: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val template = await(templateRepository.read(PathId(id), None))

      val templateId = PathId(id)

      checkAuthorization(DeleteRunSpec, Some(template), TemplateNotFoundException(templateId))

      await(templateRepository.delete(PathId(id), version))

      Response
        .ok()
        .build()
    }
  }
}

trait TemplateRepository {

  type Template = AppDefinition

  def create(template: Template): Future[Int]

  def read(pathId: PathId, version: Option[String]): Future[Template]

  def delete(pathId: PathId, version: String): Future[Done]

  def delete(pathId: PathId): Future[Done]

  def versions(pathId: PathId): Source[String, NotUsed]
}