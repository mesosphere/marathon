package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.PathMatchers.PodsPathIdLike
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth.Authenticator
import mesosphere.marathon.state.PathId
import akka.http.scaladsl.server.PathMatchers
import mesosphere.marathon.core.election.ElectionService

class PodsController(
    val electionService: ElectionService,
    val groupManager: GroupManager)(
    implicit
    val authenticator: Authenticator) extends Controller {

  import mesosphere.marathon.api.akkahttp.Directives._

  def capability(): Route =
    authenticated.apply { implicit identity =>
      complete(StatusCodes.OK)
    }

  def create(): Route = ???

  def update(podId: PathId): Route = ???

  def findAll(): Route = ???

  def find(podId: PathId): Route = ???

  def remove(podId: PathId): Route = ???

  def status(podId: PathId): Route = ???

  def versions(podId: PathId): Route = ???

  def version(podId: PathId, v: String): Route = ???

  def allStatus(): Route = ???

  def killInstance(instanceId: Instance.Id): Route = ???

  def killInstances(podId: PathId): Route = ???

  // format: OFF
  override val route: Route =
    asLeader(electionService) {
      head {
        capability()
      } ~
      get {
        pathEnd {
          findAll()
        } ~
        path("::status" ~ PathEnd) {
          allStatus()
        } ~
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            find(id)
          }
        } ~
        path(PodsPathIdLike ~ "::status" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            status(id)
          }
        } ~
        path(PodsPathIdLike ~ "::versions" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            versions(id)
          }
        } ~
        path(PodsPathIdLike ~ "::versions" / PathMatchers.Segment) { (runSpecId: String, v: String) =>
          withValidatedPathId(runSpecId) { id =>
            version(id, v)
          }
        }
      } ~
      post {
        pathEnd {
          create()
        }
      } ~
      delete {
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            remove(id)
          }
        } ~
        path(PodsPathIdLike ~ "::instances" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            killInstances(id)
          }
        } ~
        path(PodsPathIdLike ~ "::instances" / PathMatchers.Segment) { (runSpecId: String, instanceId: String) =>
          assumeValid(validatePathId(runSpecId) and validateInstanceId(instanceId)) {
            killInstance(Instance.Id(instanceId))
          }
        }
      } ~
      put {
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            update(id)
          }
        }
      }
    }
  // format: ON

}
