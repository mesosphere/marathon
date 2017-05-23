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
    def app(id: PathId, version: Option[Timestamp] = None): EntityNotFound = {
      EntityNotFound(Message(s"App '$id' does not exist" + version.fold("")(v => s" in version $v")))
    }
  }
}
