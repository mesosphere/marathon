package mesosphere.marathon.api.validation

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.main.JsonSchemaFactory

import javax.validation.ConstraintValidatorContext

import mesosphere.marathon.api.v2.json.MarathonModule
import mesosphere.jackson.CaseClassModule
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{ Container, PathId, AppDefinition }

class AppDefinitionValidatorTest extends MarathonSpec {
  var validator: AppDefinitionValidator = _
  var mapper: ObjectMapper = _

  val appDefJson = "/mesosphere/marathon/api/v2/AppDefinition.json"
  val appDefinition = JsonLoader.fromResource(appDefJson)
  val factory = JsonSchemaFactory.byDefault()
  val schema = factory.getJsonSchema(appDefinition)

  before {
    validator = new AppDefinitionValidator
    mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)
    mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
  }

  def validateJsonSchema(app: AppDefinition, valid: Boolean = true) {
    val appStr = mapper.writeValueAsString(app)
    val appJson = JsonLoader.fromString(appStr)
    assert(schema.validate(appJson).isSuccess == valid)
  }

  test("only cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("only args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("only container") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container(
        docker = Some(Container.Docker(image = "test/image"))
      )))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("empty container is invalid") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("container and cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("container and args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("container, cmd and args is not valid") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app, false)
  }
}
