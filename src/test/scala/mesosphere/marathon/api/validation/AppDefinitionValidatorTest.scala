package mesosphere.marathon.api.validation

import com.github.fge.jackson.JsonLoader

import javax.validation.ConstraintValidatorContext

import mesosphere.marathon.state.{ Container, PathId, AppDefinition }
import mesosphere.marathon.MarathonSpec

import scala.collection.JavaConversions._

class AppDefinitionValidatorTest extends MarathonSpec {
  var validator: AppDefinitionValidator = _

  before {
    validator = new AppDefinitionValidator
  }

  def validateJson(app: AppDefinition, valid: Boolean = true) {
    val appStr = schemaMapper.writeValueAsString(app)
    val appJson = JsonLoader.fromString(appStr)

    // write/read results in same app
    // write/read/write results in same json
    val rereadApp = schemaMapper.readValue(appStr, classOf[AppDefinition])
    val rereadAppJson = schemaMapper.writeValueAsString(rereadApp)
    assert(appStr == rereadAppJson) // first compare the strings since that results in better error messages
    assert(app == rereadApp)

    // schema is correct
    val validationResult = appSchema.validate(appJson)
    assert(validationResult.isSuccess == valid, validationResult.iterator().map(_.getMessage).mkString("\n"))
  }

  test("only cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("only cmd + acceptedResourceRoles") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      acceptedResourceRoles = Some(Set("*")))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("only cmd + acceptedResourceRoles 2") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      acceptedResourceRoles = Some(Set("*", "production")))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("only args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("only container") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container(
        docker = Some(Container.Docker(image = "test/image"))
      )))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("empty container is invalid") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("container and cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("container and args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app)
  }

  test("container, cmd and args is not valid") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJson(app, false)
  }
}
