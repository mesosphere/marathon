package mesosphere.marathon.api

import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

import mesosphere.marathon.state.{ Timestamp, PathId }

/**
  * @author Tobi Knaup
  */

object Responses {

  def unknownGroup(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"Group '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  def unknownTask(id: String): Response = notFound(s"Task '$id' does not exist")

  def unknownApp(id: PathId, version: Option[Timestamp] = None): Response = {
    notFound(s"App '$id' does not exist" + version.fold("")(v => s" in version $v"))
  }

  def notFound(message: String): Response = {
    Response.status(Status.NOT_FOUND).entity(Map("message" -> message)).build
  }
}
