package mesosphere.marathon
package api.validation

import mesosphere.UnitTest
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.state._

import scala.collection.immutable.Seq

class AppDefinitionReadinessCheckValidationTest extends UnitTest {

  lazy val validAppDefinition = AppDefinition.validAppDefinition(Set())(PluginManager.None)
  "AppDefinitionReadinessValidation" should {
    "app with 0 readinessChecks is valid" in {
      val f = new Fixture
      Given("an app without readinessChecks")
      val app = f.app(
        readinessChecks = Nil
      )

      Then("the app is considered valid")
      validAppDefinition(app).isSuccess shouldBe true
    }

    "app with 1 readinessCheck is valid" in {
      val f = new Fixture
      Given("an app with one readinessCheck")
      val app = f.app(
        readinessChecks = Seq(ReadinessCheck())
      )

      Then("the app is considered valid")
      validAppDefinition(app).isSuccess shouldBe true
    }

    "app with more than 1 readinessChecks is invalid" in {
      val f = new Fixture
      Given("a app app with more than one readiness checks")
      val app = f.app(readinessChecks = Seq(ReadinessCheck(), ReadinessCheck()))

      Then("validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "app with invalid readinessChecks is invalid" in {
      val f = new Fixture
      Given("a app app with an invalid readiness check")
      val app = f.app(readinessChecks = Seq(ReadinessCheck(name = "")))

      Then("validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "readinessCheck NOT corresponding to port definitions are invalid" in {
      val f = new Fixture
      Given("a app with ports and a readinessCheck that uses an unknown portName")
      val portName = "foo"
      val app = f.app(
        readinessChecks = Seq(ReadinessCheck(portName = "invalid")),
        portDefinitions = Seq(PortDefinition(port = 123, name = Some(portName)))
      )

      Then("validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "readinessCheck corresponding to port definitions are valid" in {
      val f = new Fixture
      Given("a app with ports and a readinessCheck that uses an unknown portName")
      val portName = "foo"
      val app = f.app(
        readinessChecks = Seq(ReadinessCheck(portName = portName)),
        portDefinitions = Seq(PortDefinition(port = 123, name = Some(portName)))
      )

      Then("validation fails")
      validAppDefinition(app).isSuccess shouldBe true
    }

    class Fixture {
      def app(
        readinessChecks: Seq[ReadinessCheck] = Seq(ReadinessCheck()),
        portDefinitions: Seq[PortDefinition] = Seq(
          PortDefinition(port = 123, name = Some(ReadinessCheck.DefaultPortName))
        )): AppDefinition =

        AppDefinition(
          id = PathId("/test"),
          cmd = Some("sleep 1000"),
          instances = 1,
          readinessChecks = readinessChecks,
          portDefinitions = portDefinitions
        )
    }

  }
}