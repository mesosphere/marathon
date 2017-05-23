package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.server.Route

/**
  * Very simple interface to help enforce consistency between Controllers
  *
  * General guidelines for designing controllers:
  *
  * - All paths and HTTP verbs are handled directly inside of routes
  * - Each action is handled by a method that returns a route; this route will extract the parameters it needs to
  *   perform its function, and complete with the appropriate status code, or reject with the appropriate rejection
  * - Avoid rendering a custom status code in the event of some exception (IE entity not found); instead, use a
  *   rejection
  * - Import the akkahttp Directives
  */
trait Controller {
  val route: Route
}
