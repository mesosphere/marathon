package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.{ Controller, HttpPluginFacade }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.plugin.PluginDefinitions
import mesosphere.marathon.plugin.auth.AuthorizedResource.Plugins
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.HttpRequestHandler

/**
  * The PluginsController can list all available installed plugins,
  * as well as serve requests for all installed HttpRequestHandler plugins.
  *
  * A plugin that implement HttpRequestHandler can be registered via the plugin id.
  * All traffic routed to /v2/plugins/{pluginId}/{remaining} is handled via the plugin with given pluginId.
  * The path that is given to the plugin is {remaining}.
  */
class PluginsController(
    requestHandlers: Seq[HttpRequestHandler],
    definitions: PluginDefinitions)(implicit
  val authenticator: Authenticator,
    authorizer: Authorizer,
    electionService: ElectionService) extends Controller {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  private[this] val pluginIdToHandler: Map[String, HttpRequestHandler] = definitions.plugins
    .withFilter(_.plugin == classOf[HttpRequestHandler].getName)
    .flatMap { d => requestHandlers.find(_.getClass.getName == d.implementation).map(d.id -> _) }(collection.breakOut)

  // Path matcher, that matches a valid plugin id, based on the available plugins
  object ValidPluginId extends PathIsAvailableInSet(pluginIdToHandler.keySet)

  /**
    * GET /v2/plugins
    * @return plugin definitions for all available plugins
    */
  def plugins: Route = complete(definitions)

  /**
    * METHOD /v2/plugins/{pluginId}/{path}
    * @param pluginId the id of the plugin
    * @param path the remaining path passed to the plugin
    * @return the response created by the plugin.
    */
  def pluginRoute(pluginId: String, path: String): Route =
    extractRequest { request =>
      extractClientIP { clientIp =>
        val handler = pluginIdToHandler(pluginId)
        val pluginRequest = HttpPluginFacade.request(request, clientIp, Some(path))
        val response = HttpPluginFacade.response(handler.serve(pluginRequest, _))
        complete(response)
      }
    }

  override val route: Route =
    asLeader(electionService) {
      authenticated.flatMap(implicit identity => authorized(Plugins)).apply {
        pathEnd {
          get { plugins }
        } ~
          path(ValidPluginId / Remaining) { (pluginId, path) => pluginRoute(pluginId, path) }
      }
    }
}
