package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, Response }

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ MarathonMediaType, RequestFacade, ResponseFacade, RestResource }
import mesosphere.marathon.core.plugin.PluginDefinitions
import mesosphere.marathon.plugin.http.{ HttpRequest, HttpRequestHandler, HttpResponse }

@Path("v2/plugins")
class PluginsResource @Inject() (val config: MarathonConf,
                                 requestHandlers: Seq[HttpRequestHandler],
                                 definitions: PluginDefinitions) extends RestResource {

  val pluginIdToHandler = definitions.plugins
    .filter(_.plugin == classOf[HttpRequestHandler].getName)
    .flatMap { d => requestHandlers.find(_.getClass.getName == d.implementation).map(d.id -> _) }
    .toMap

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def plugins(): Response = ok(jsonString(definitions))

  @GET
  @Path("""{pluginId}/{path:.+}""")
  def get(@PathParam("pluginId") pluginId: String,
          @PathParam("path") path: String,
          @Context req: HttpServletRequest): Response = handleRequest(pluginId, path, req)

  @HEAD
  @Path("""{pluginId}/{path:.+}""")
  def head(@PathParam("pluginId") pluginId: String,
           @PathParam("path") path: String,
           @Context req: HttpServletRequest): Response = handleRequest(pluginId, path, req)

  @PUT
  @Path("""{pluginId}/{path:.+}""")
  def put(@PathParam("pluginId") pluginId: String,
          @PathParam("path") path: String,
          @Context req: HttpServletRequest): Response = handleRequest(pluginId, path, req)

  @POST
  @Path("""{pluginId}/{path:.+}""")
  def post(@PathParam("pluginId") pluginId: String,
           @PathParam("path") path: String,
           @Context req: HttpServletRequest): Response = handleRequest(pluginId, path, req)

  @DELETE
  @Path("""{pluginId}/{path:.+}""")
  def delete(@PathParam("pluginId") pluginId: String,
             @PathParam("path") path: String,
             @Context req: HttpServletRequest): Response = handleRequest(pluginId, path, req)

  private[this] def handleRequest(pluginId: String, path: String, req: HttpServletRequest): Response = {
    pluginIdToHandler.get(pluginId).map { handler =>
      val request = new RequestFacade(req, path)
      val response = new ResponseFacade
      handler.serve(request, response)
      response.response
    }.getOrElse(notFound(s"No plugin with this pluginId: $pluginId"))
  }
}
