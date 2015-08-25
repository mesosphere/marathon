package mesosphere.marathon.api

import javax.validation.ConstraintViolationException

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.BeanValidation
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.state.PathId
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsObject, JsResultException, Json }

class MarathonExceptionMapperTest extends MarathonSpec with GivenWhenThen with Matchers {

  test("Render json parse exception correctly") {
    Given("A JsResultException, from an invalid json to object Reads")
    val ex = intercept[JsResultException] { Json.parse("""{"id":123}""").as[V2AppDefinition] }
    val mapper = new MarathonExceptionMapper()

    When("The mapper creates a response from this exception")
    val response = mapper.toResponse(ex)

    Then("The correct response is created")
    response.getStatus should be(400)
    val entityString = response.getEntity.asInstanceOf[String]
    val entity = Json.parse(entityString)
    (entity \ "message").as[String] should be("Invalid JSON")
    val details = (entity \ "details").as[Seq[JsObject]]
    details should have size 1
    val firstDetail = details.head
    (firstDetail \ "path").as[String] should be("/id")
    val errors = (firstDetail \ "errors").as[Seq[String]]
    errors should have size 1
    errors.head should be("error.expected.jsstring")
  }

  test("Render ConstraintValidationException correctly") {
    Given("A ConstraintValidationException from an invalid app")
    val violations = BeanValidation.validate(V2AppDefinition(id = PathId("/test")))
    val ex = intercept[ConstraintViolationException] { BeanValidation.requireValid(violations) }
    val mapper = new MarathonExceptionMapper()

    When("The mapper creates a response from this exception")
    val response = mapper.toResponse(ex)

    Then("The correct response is created")
    response.getStatus should be(422)
    val entityString = response.getEntity.asInstanceOf[String]
    val entity = Json.parse(entityString)
    (entity \ "message").as[String] should be("Bean is not valid")
    val errors = (entity \ "errors").as[Seq[JsObject]]
    errors should have size 1
    val firstError = errors.head
    (firstError \ "attribute").as[String] should be("")
    (firstError \ "error").as[String] should be("AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.")
  }
}
