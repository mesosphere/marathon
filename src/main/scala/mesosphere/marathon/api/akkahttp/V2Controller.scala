package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.server.Route
import v2._

/**
  * Dispatches to various v2 controller routes
  */
class V2Controller(
    appsController: AppsController,
    eventsController: EventsController,
    pluginsController: PluginsController,
    infoController: InfoController,
    leaderController: LeaderController,
    queueController: QueueController,
    tasksController: TasksController,
    podsController: PodsController
) extends Controller {
  import Directives._
  override val route: Route = {
    pathPrefix("apps") {
      appsController.route
    } ~
      pathPrefix("pods") {
        podsController.route
      } ~
      pathPrefix("events") {
        eventsController.route
      } ~
      pathPrefix("plugins") {
        pluginsController.route
      } ~
      pathPrefix("info") {
        infoController.route
      } ~
      pathPrefix("leader") {
        leaderController.route
      } ~
      pathPrefix("queue") {
        queueController.route
      } ~
      pathPrefix("tasks") {
        tasksController.route
      }
  }
}
