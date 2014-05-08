package mesosphere.marathon.api

import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

/**
 * @author Tobi Knaup
 */

object Responses {

  def unknownApp(id: String): Response = {
    Response
      .status(Status.NOT_FOUND)
      .entity(Map("message" -> s"App '$id' does not exist"))
      .build
  }

  def unknownTask(id: String): Response = {
    Response
      .status(Status.NOT_FOUND)
      .entity(Map("message" -> s"Task '$id' does not exist"))
      .build
  }
}
