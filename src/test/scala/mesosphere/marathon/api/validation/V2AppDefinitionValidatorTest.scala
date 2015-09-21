package mesosphere.marathon.api.validation

import javax.validation.ConstraintValidatorContext

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.HealthCheckDefinition
import mesosphere.marathon.api.v2.{ ModelValidation, BeanValidation }
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ Command, Container, PathId }
import org.scalatest.Matchers

class V2AppDefinitionValidatorTest extends MarathonSpec with Matchers {
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

  private[this] def testValidId(id: String): Unit = {
    val app = V2AppDefinition(
      id = PathId(id),
      cmd = Some("true"))

    BeanValidation.requireValid(ModelValidation.checkAppConstraints(app, app.id.parent))

    validateJsonSchema(app)
  }

  test("id '/app' is valid") {
    testValidId("/app")
  }

  test("id '/hy-phenated' is valid") {
    testValidId("/hy-phenated")
  }

  test("id '/numbered9' is valid") {
    testValidId("/numbered9")
  }

  test("id '/9numbered' is valid") {
    testValidId("/9numbered")
  }

  test("id '/num8bered' is valid") {
    testValidId("/num8bered")
  }

  test("id '/dot.ted' is valid") {
    testValidId("/dot.ted")
  }

  test("id '/deep/ly/nes/ted' is valid") {
    testValidId("/deep/ly/nes/ted")
  }

  test("id '/all.to-9gether/now-huh12/nest/nest' is valid") {
    testValidId("/all.to-9gether/now-huh12/nest/nest")
  }

  test("id '/trailing/' is valid") {
    // the trailing slash is apparently ignored by Marathon
    testValidId("/trailing/")
  }

  test("single dots in id '/test/.' pass schema and validation") {
    testInvalid("/test/.")
  }

  test("single dots in id '/./not.point.less' pass schema and validation") {
    testInvalid("/./not.point.less")
  }

  private[this] def testSchemaLessStrictForId(id: String): Unit = {
    val app = V2AppDefinition(
      id = PathId(id),
      cmd = Some("true"))

    an[IllegalArgumentException] should be thrownBy {
      ModelValidation.checkAppConstraints(app, app.id.parent)
    }

    validateJsonSchema(app)
  }

  // non-absolute paths (could be allowed in some contexts)
  test(s"relative id 'relative/asd' passes schema but not validation") {
    testSchemaLessStrictForId("relative/asd")
  }

  // non-absolute paths (could be allowed in some contexts)
  test(s"relative id '../relative' passes schema but not validation") {
    testSchemaLessStrictForId("../relative")
  }

  private[this] def testInvalid(id: String): Unit = {
    val app = V2AppDefinition(
      id = PathId(id),
      cmd = Some("true")
    )

    ModelValidation.checkAppConstraints(app, app.id.parent) should not be empty

    validateJsonSchema(app, valid = false)
  }

  test("id '/.../asd' is INVALID") {
    testInvalid("/.../asd")
  }

  test("id '/app!' is INVALID") {
    testInvalid("/app!' i")
  }

  test("id '/app[' is INVALID") {
    testInvalid("/app[' i")
  }

  test("id '/asd/sadf+' is INVALID") {
    testInvalid("/asd/sadf+")
  }

  test("id '/asd asd' is INVALID") {
    testInvalid("/asd asd")
  }

  test("id '/app-' is invalid because hyphens and dots are only allowed inside of path fragments") {
    testInvalid("/app-")
  }

  test("id '/nest./ted' is invalid because hyphens and dots are only allowed inside of path fragments") {
    testInvalid("/nest./ted")
  }

  test("id '/nest/-ted' is invalid because hyphens and dots are only allowed inside of path fragments") {
    testInvalid("/nest/-ted")
  }

  test("only cmd + command health check") {
    val app = V2AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      healthChecks = Set(
        HealthCheck(
          protocol = HealthCheckDefinition.Protocol.COMMAND,
          command = Some(Command("curl http://localhost:$PORT"))
        )
      )
    )
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
