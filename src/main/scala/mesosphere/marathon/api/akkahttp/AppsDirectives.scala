package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.server.{ Directive1, MalformedQueryParamRejection }
import akka.http.scaladsl.server.Directives._

object AppsDirectives {
  def extractTaskKillingMode: Directive1[TaskKillingMode] =
    (parameter("scale".as[Boolean].?(false)) & parameter("wipe".as[Boolean].?(false))).tflatMap {
      case (scale, wipe) =>
        if (scale && wipe) {
          reject(MalformedQueryParamRejection("scale, wipe", "You cannot use scale and wipe at the same time."))
        } else if (scale) {
          provide(TaskKillingMode.Scale)
        } else if (wipe) {
          provide(TaskKillingMode.Wipe)
        } else {
          provide(TaskKillingMode.KillWithoutWipe)
        }
    }

  sealed trait TaskKillingMode
  object TaskKillingMode {

    case object Scale extends TaskKillingMode

    case object KillWithoutWipe extends TaskKillingMode

    case object Wipe extends TaskKillingMode

  }

}
