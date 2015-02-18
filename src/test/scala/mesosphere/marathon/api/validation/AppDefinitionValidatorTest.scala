package mesosphere.marathon.api.validation

import javax.validation.ConstraintValidatorContext

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{Container, PathId, AppDefinition}

class AppDefinitionValidatorTest extends MarathonSpec {
  var validator: AppDefinitionValidator = _

  before {
    validator = new AppDefinitionValidator
  }

  test("only cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
  }

  test("only args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
  }

  test("only container") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container(
        docker = Some(Container.Docker(image = "test/image"))
      )))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
  }

  test("empty container is invalid") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
  }

  test("container and cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
  }

  test("container and args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(validator.isValid(app, mock[ConstraintValidatorContext]))
  }

  test("container, cmd and args is not valid") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      args = Some("test" :: Nil),
      container = Some(Container()))
    assert(!validator.isValid(app, mock[ConstraintValidatorContext]))
  }
}
