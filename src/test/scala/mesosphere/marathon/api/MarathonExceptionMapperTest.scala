package mesosphere.marathon
package api

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.raml.App
import play.api.libs.json.{ JsObject, JsResultException, Json }

class MarathonExceptionMapperTest extends UnitTest {
  implicit lazy val validAppDefinition = AppDefinition.validAppDefinition(Set.empty[String])(PluginManager.None)

  "MarathonExceptionMapper" should {
    "Render js result exception correctly" in {
      Given("A JsResultException, from an invalid json to object Reads")
      val ex = intercept[JsResultException] { Json.parse("""{"id":123}""").as[App] }
      val mapper = new MarathonExceptionMapper()

      When("The mapper creates a response from this exception")
      val response = mapper.toResponse(ex)

      Then("The correct response is created")
      response.getStatus should be(422)
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

    "Render json parse exception correctly" in {
      Given("A JsonParseException, from an invalid json to object Reads")
      val ex = intercept[JsonParseException] { Json.parse("""{"id":"/test"""").as[App] }
      val mapper = new MarathonExceptionMapper()

      When("The mapper creates a response from this exception")
      val response = mapper.toResponse(ex)

      Then("The correct response is created")
      response.getStatus should be(400)
      val entityString = response.getEntity.asInstanceOf[String]
      val entity = Json.parse(entityString)
      (entity \ "message").as[String] should be("Invalid JSON")
      (entity \ "details").as[String] should be("""Unexpected end-of-input: expected close marker for OBJECT (from [Source: {"id":"/test"; line: 1, column: 1])""")
    }

    "Render json mapping exception correctly" in {
      Given("A JsonMappingException, from an invalid json to object Reads")
      val ex = intercept[JsonMappingException] { Json.parse("").as[App] }
      val mapper = new MarathonExceptionMapper()

      When("The mapper creates a response from this exception")
      val response = mapper.toResponse(ex)

      Then("The correct response is created")
      response.getStatus should be(400)
      val entityString = response.getEntity.asInstanceOf[String]
      val entity = Json.parse(entityString)
      (entity \ "message").as[String] should be("Please specify data in JSON format")
      (entity \ "details").as[String] should be("No content to map due to end-of-input\n at [Source: ; line: 1, column: 0]")
    }

    "Render ConstraintValidationException correctly" in {
      Given("A ConstraintValidationException from an invalid app")
      val ex = intercept[ValidationFailedException] { validateOrThrow(AppDefinition(id = PathId("/test"))) }
      val mapper = new MarathonExceptionMapper()

      When("The mapper creates a response from this exception")
      val response = mapper.toResponse(ex)

      Then("The correct response is created")
      response.getStatus should be(422)
      val entityString = response.getEntity.asInstanceOf[String]
      val entity = Json.parse(entityString)
      (entity \ "message").as[String] should be("Object is not valid")
      val errors = (entity \ "details").as[Seq[JsObject]]
      errors should have size 1
      val firstError = errors.head
      (firstError \ "path").as[String] should be("/")
      val errorMsgs = (firstError \ "errors").as[Seq[String]]
      errorMsgs.head should be("AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.")
    }
  }
}
