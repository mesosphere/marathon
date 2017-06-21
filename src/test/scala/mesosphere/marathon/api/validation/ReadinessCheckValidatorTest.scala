package mesosphere.marathon
package api.validation

import com.wix.accord.{ Validator, validate }
import mesosphere.UnitTest
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.state.PortDefinition
import mesosphere.marathon.test.MarathonTestHelper

import scala.collection.immutable._
import scala.concurrent.duration._

class ReadinessCheckValidatorTest extends UnitTest {

  import mesosphere.marathon.test.MarathonTestHelper.Implicits._

  "ReadinessCheckValidator" should {
    "default is valid" in new Fixture {
      Given("a default readiness check instance")
      val rc = ReadinessCheck()

      Then("validation succeeds")
      validate(rc).isSuccess shouldBe true
    }

    "empty name is invalid" in new Fixture {
      Given("a readiness check without a name")
      val rc = ReadinessCheck(name = "")

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "empty path is invalid" in new Fixture {
      Given("a readiness check with an empty path")
      val rc = ReadinessCheck(path = "")

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "empty portName is invalid" in new Fixture {
      Given("a readiness check with an empty portName")
      val rc = ReadinessCheck(portName = "")

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "unknown portName is invalid" in new Fixture {
      Given("a readiness check with an unknown portName")
      val rc = ReadinessCheck(portName = "unknown")

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "interval == 0 is invalid" in new Fixture {
      Given("a readiness check with a 0 interval")
      val rc = ReadinessCheck(interval = 0.seconds)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "interval < 0 is invalid" in new Fixture {
      Given("a readiness check with a negative interval")
      val rc = ReadinessCheck(interval = -10.seconds)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "timeout == 0 is invalid" in new Fixture {
      Given("a readiness check with a 0 timeout")
      val rc = ReadinessCheck(timeout = 0.seconds)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "timeout < 0 is invalid" in new Fixture {
      Given("a readiness check with a negative timeout")
      val rc = ReadinessCheck(interval = -10.seconds)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "timeout < interval is valid" in new Fixture {
      Given("a readiness check with a timeout which is smaller than the interval")
      val rc = ReadinessCheck(timeout = 3.seconds, interval = 10.seconds)

      Then("validation succeeds")
      validate(rc).isSuccess shouldBe true
    }

    "timeout == interval is invalid" in new Fixture {
      Given("a readiness check with a timeout which is smaller equal to the interval")
      val rc = ReadinessCheck(timeout = 3.seconds, interval = 3.seconds)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "timeout > interval is invalid" in new Fixture {
      Given("a readiness check with a timeout which is greater than the interval")
      val rc = ReadinessCheck(timeout = 10.seconds, interval = 3.seconds)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }

    "empty httpStatusCodesForReady is invalid" in new Fixture {
      Given("a readiness check with no defined httpStatusCodesForReady")
      val rc = ReadinessCheck(httpStatusCodesForReady = Set.empty)

      Then("validation fails")
      validate(rc).isFailure shouldBe true
    }
  }
  class Fixture {
    val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(
      Seq(
        PortDefinition(
          port = 123,
          name = Some(ReadinessCheck.DefaultPortName))))
    implicit val readinessCheckValidator: Validator[ReadinessCheck] = ReadinessCheck.readinessCheckValidator(app)
  }
}
