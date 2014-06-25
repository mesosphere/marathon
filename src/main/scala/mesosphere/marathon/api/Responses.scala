package mesosphere.marathon.api

import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

import mesosphere.marathon.state.{ Timestamp, PathId }

/**
  * @author Tobi Knaup
  */

object Responses {

  def unknownGroup(id: PathId): Response = {
    Response
      .status(Status.NOT_FOUND)
      .entity(Map("message" -> s"Group '$id' does not exist"))
      .build
  }

  def unknownApp(id: PathId, version: Option[Timestamp] = None): Response = {
    var text = s"App '$id' does not exist"
    version.map(v => text += s" in version $v")
    Response
      .status(Status.NOT_FOUND)
      .entity(Map("message" -> text))
      .build
  }

  def unknownTask(id: String): Response = {
    Response
      .status(Status.NOT_FOUND)
      .entity(Map("message" -> s"Task '$id' does not exist"))
      .build
  }
}
