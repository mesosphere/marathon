package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.Directives.extractInstanceId
import mesosphere.marathon.api.akkahttp.PathMatchers.ExistingRunSpecId
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth.Authenticator
import mesosphere.marathon.state.PathId

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

  def version(version: String): Route = ???

  def allStatus(): Route = ???

  def killInstance(instanceId: Instance.Id): Route = ???

  def killInstances(podId: PathId): Route = ???

  // format: OFF
  override val route: Route =
    head {
      capability()
    } ~
    get {
      findAll()
    } ~
    post {
      create()
    } ~
    pathPrefix(ExistingRunSpecId(groupManager.rootGroup)) { podId =>
      put {
        update(podId)
      } ~
      get {
        pathEnd {
          find(podId)
        } ~
        pathPrefix("::status") {
          status(podId)
        } ~
        pathPrefix("::versions") {
          pathEnd {
            versions(podId)
          } ~
          extractUnmatchedPath { v =>
            version(v.toString)
          }
        }
      } ~
      delete {
        pathEnd {
          remove(podId)
        } ~
        pathPrefix("::instances") {
          pathEnd {
            killInstances(podId)
          } ~
          extractInstanceId { instanceId =>
            killInstance(instanceId)
          }
        }
      }
    } ~
    pathPrefix("::status") {
      allStatus()
    }
  // format: ON

}
