package mesosphere.chaos.example

import javax.ws.rs.{ POST, Produces, Path, GET }
import javax.ws.rs.core.MediaType
import com.codahale.metrics.annotation.Timed
import javax.validation.Valid
import scala.util.Random

@Path("foo")
@Produces(Array(MediaType.APPLICATION_JSON))
class ExampleResource {

  private final val names = Seq("Dude", "Walter", "Donny", "Jesus")

  @GET
  @Timed
  def get() = {
    val person = new Person
    person.name = names(Random.nextInt(names.size))
    person.age = Random.nextInt(150)
    person
  }

  @POST
  @Timed
  def post(@Valid person: Person) {
    println(person)
  }

  @Path("bar")
  def bar() = new ExampleSubResource
}
