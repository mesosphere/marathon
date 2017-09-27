package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{Authenticator, AuthorizedResource, Authorizer}

class InfoController(implicit
  val authenticator: Authenticator,
  val authorizer: Authorizer,
  val electionService: ElectionService) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._

  def info(): Route = complete { ??? }

  override val route: Route = {
    asLeader(electionService) {
      get {
        pathEnd {
          info()
        }
      }
    }
  }
}
