package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.server.Route
import v2.AppsController

/**
  * Dispatches to various v2 controller routes
  */
class V2Controller(appsController: AppsController) extends Controller {
  import Directives._
  override val route: Route = {
    pathPrefix("apps") {
      appsController.route
    }
  }
}
