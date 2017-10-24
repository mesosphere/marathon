package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.Directives.extractInstanceId
import mesosphere.marathon.api.akkahttp.PathMatchers.{ ExistingRunSpecId, PodsPathIdLike }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth.Authenticator
import mesosphere.marathon.state.PathId
import akka.http.scaladsl.server.PathMatchers

class PodsController(
    val groupManager: GroupManager)(
    implicit
    val authenticator: Authenticator) extends Controller {

  import mesosphere.marathon.api.akkahttp.Directives._

  def capability(): Route =
    authenticated.apply { implicit identity =>
      complete("")
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
        assumeValid(validatePathId(runSpecId)) {
          find(PathId(runSpecId))
        }
      } ~
      path(PodsPathIdLike ~ "::status" ~ PathEnd) { runSpecId: String =>
        assumeValid(validatePathId(runSpecId)) {
          status(PathId(runSpecId))
        }
      } ~
      path(PodsPathIdLike ~ "::versions" ~ PathEnd) { runSpecId: String =>
        assumeValid(validatePathId(runSpecId)) {
          versions(PathId(runSpecId))
        }
      } ~
      path(PodsPathIdLike ~ "::versions" / PathMatchers.Segment) { (runSpecId: String, v: String) =>
        assumeValid(validatePathId(runSpecId)) {
          version(PathId(runSpecId), v)
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
        assumeValid(validatePathId(runSpecId)) {
          remove(PathId(runSpecId))
        }
      } ~
      path(PodsPathIdLike ~ "::instances" ~ PathEnd) { runSpecId: String =>
        assumeValid(validatePathId(runSpecId)) {
          killInstances(PathId(runSpecId))
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
        assumeValid(validatePathId(runSpecId)) {
          update(PathId(runSpecId))
        }
      }
    }
  // format: ON

}
