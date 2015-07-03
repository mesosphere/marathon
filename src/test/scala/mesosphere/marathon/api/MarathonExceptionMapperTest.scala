package mesosphere.marathon.api

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsResultException, Json }

class MarathonExceptionMapperTest extends MarathonSpec with GivenWhenThen with Matchers {

  test("Render json parse exception correctly") {
    Given("A JsResultException, from an invalid json to object Reads")
    val ex = intercept[JsResultException] { Json.parse("""{"id":123}""").as[V2AppDefinition] }
    val mapper = new MarathonExceptionMapper()

    When("The mapper creates a response from this exception")
    val response = mapper.toResponse(ex)

    Then("The correct response is created")
    response.getStatus should be(400)
    val entity = response.getEntity.asInstanceOf[Map[String, AnyRef]]
    entity("message") should be("Invalid JSON")
    val details = entity("details").asInstanceOf[List[Map[String, Seq[String]]]]
    details should have size 1
    details.head("path") should be("/id")
    details.head("errors") should have size 1
    details.head("errors").head should be("error.expected.jsstring")
  }
}
