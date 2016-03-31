package mesosphere.marathon.api.validation

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.state._
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class AppDefinitionReadinessCheckValidationTest extends MarathonSpec with Matchers with GivenWhenThen {

  test("app with 0 readinessChecks is valid") {
    val f = new Fixture
    Given("an app without readinessChecks")
    val app = f.app(
      readinessChecks = Nil
    )

    Then("the app is considered valid")
    AppDefinition.validAppDefinition(app).isSuccess shouldBe true
  }

  test("app with 1 readinessCheck is valid") {
    val f = new Fixture
    Given("an app with one readinessCheck")
    val app = f.app(
      readinessChecks = Seq(ReadinessCheck())
    )

    Then("the app is considered valid")
    AppDefinition.validAppDefinition(app).isSuccess shouldBe true
  }

  test("app with more than 1 readinessChecks is invalid") {
    val f = new Fixture
    Given("a app app with more than one readiness checks")
    val app = f.app(readinessChecks = Seq(ReadinessCheck(), ReadinessCheck()))

    Then("validation fails")
    AppDefinition.validAppDefinition(app).isFailure shouldBe true
  }

  test("app with invalid readinessChecks is invalid") {
    val f = new Fixture
    Given("a app app with an invalid readiness check")
    val app = f.app(readinessChecks = Seq(ReadinessCheck(name = "")))

    Then("validation fails")
    AppDefinition.validAppDefinition(app).isFailure shouldBe true
  }

  test("readinessCheck NOT corresponding to port definitions are invalid") {
    val f = new Fixture
    Given("a app with ports and a readinessCheck that uses an unknown portName")
    val portName = "foo"
    val app = f.app(
      readinessChecks = Seq(ReadinessCheck(portName = "invalid")),
      portDefinitions = Seq(PortDefinition(port = 123, name = Some(portName)))
    )

    Then("validation fails")
    AppDefinition.validAppDefinition(app).isFailure shouldBe true
  }

  test("readinessCheck corresponding to port definitions are valid") {
    val f = new Fixture
    Given("a app with ports and a readinessCheck that uses an unknown portName")
    val portName = "foo"
    val app = f.app(
      readinessChecks = Seq(ReadinessCheck(portName = portName)),
      portDefinitions = Seq(PortDefinition(port = 123, name = Some(portName)))
    )

    Then("validation fails")
    AppDefinition.validAppDefinition(app).isSuccess shouldBe true
  }

  class Fixture {
    def app(
      readinessChecks: Seq[ReadinessCheck] = Seq(ReadinessCheck()),
      portDefinitions: Seq[PortDefinition] = Seq(
        PortDefinition(port = 123, name = Some(ReadinessCheck.DefaultPortName))
      )): AppDefinition =

      AppDefinition(
        cmd = Some("sleep 1000"),
        instances = 1,
        readinessChecks = readinessChecks,
        portDefinitions = portDefinitions
      )
  }

}
