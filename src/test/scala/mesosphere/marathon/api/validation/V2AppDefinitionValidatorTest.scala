package mesosphere.marathon.api.validation

import com.github.fge.jackson.JsonLoader

import javax.validation.ConstraintValidatorContext

import mesosphere.jackson.CaseClassModule
import mesosphere.marathon.api.v2.json.{ MarathonModule, V2AppDefinition }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{ Container, PathId, AppDefinition }
import mesosphere.marathon.MarathonSpec

class V2AppDefinitionValidatorTest extends MarathonSpec {
  var validator: V2AppDefinitionValidator = _

  before {
    validator = new V2AppDefinitionValidator
  }

  test("only cmd") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("only cmd + acceptedResourceRoles") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      acceptedResourceRoles = Some(Set("*")))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("only cmd + acceptedResourceRoles 2") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      acceptedResourceRoles = Some(Set("*", "production")))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("only args") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("only container") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      container = Some(Container(
        docker = Some(Container.Docker(image = "test/image"))
      )))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("empty container is invalid") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("container and cmd") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("container and args") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app)
  }

  test("container, cmd and args is not valid") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
    validateJsonSchema(app, false)
  }
}
