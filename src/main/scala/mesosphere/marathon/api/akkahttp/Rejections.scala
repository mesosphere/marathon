package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.server._
import mesosphere.marathon.core.task.Task
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

    private def readableVersion(version: Option[Timestamp]) = version.fold("")(v => s" in version $v")

    def noApp(id: PathId, version: Option[Timestamp] = None): EntityNotFound = {
      EntityNotFound(Message(s"App '$id' does not exist${readableVersion(version)}"))
    }
    def noGroup(id: PathId, version: Option[Timestamp] = None): EntityNotFound = {
      EntityNotFound(Message(s"Group '$id' does not exist${readableVersion(version)}"))
    }
    def noLeader(): EntityNotFound = {
      EntityNotFound(Message("There is no leader"))
    }
    def noPod(id: PathId, version: Option[Timestamp] = None): EntityNotFound = {
      EntityNotFound(Message(s"Pod '$id' does not exist${readableVersion(version)}"))
    }
    def noPod(id: PathId, version: String): EntityNotFound = {
      EntityNotFound(Message(s"Pod '$id' does not exist in version $version"))
    }

    def queueApp(appId: PathId): EntityNotFound = {
      EntityNotFound(Message(s"Application $appId not found in tasks queue."))
    }

    def noTask(id: Task.Id): EntityNotFound = {
      EntityNotFound(Message(s"Task '$id' does not exist"))
    }
  }

  case class BadRequest(message: Message) extends Rejection
  case class ConflictingChange(message: Message) extends Rejection
}
