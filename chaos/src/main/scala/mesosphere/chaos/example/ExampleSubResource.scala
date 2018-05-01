package mesosphere.chaos.example

import javax.ws.rs.{ Produces, GET }
import scala.Array
import javax.ws.rs.core.MediaType

@Produces(Array(MediaType.APPLICATION_JSON))
class ExampleSubResource {

  @GET
  def get() = {
    System.getProperties
  }
}
