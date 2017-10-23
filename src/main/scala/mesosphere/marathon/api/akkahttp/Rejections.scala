package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.server._
import mesosphere.marathon.state.{ PathId, Timestamp }
import play.api.libs.json.Json

object Rejections {
  case class Message(message: String)
  object Message {
    implicit val messageFormat = Json.format[Message]
  }

  val defaultEntityNotFoundMessage = Message("Entity was not found")

  case class EntityNotFound(message: Message = defaultEntityNotFoundMessage) extends Rejection
  object EntityNotFound {
    def noApp(id: PathId, version: Option[Timestamp] = None): EntityNotFound = {
      EntityNotFound(Message(s"App '$id' does not exist" + version.fold("")(v => s" in version $v")))
    }
    def noLeader(): EntityNotFound = {
      EntityNotFound(Message("There is no leader"))
    }

    def queueApp(appId: PathId): EntityNotFound = {
      EntityNotFound(Message(s"Application $appId not found in tasks queue."))
    }
  }

  case class BadRequest(message: Message) extends Rejection
}
