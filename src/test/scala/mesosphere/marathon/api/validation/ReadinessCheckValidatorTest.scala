package mesosphere.marathon.api.validation

import com.wix.accord.validate
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.state.PortDefinition
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable._
import scala.concurrent.duration._

class ReadinessCheckValidatorTest extends MarathonSpec with Matchers with GivenWhenThen {
  import mesosphere.marathon.MarathonTestHelper.Implicits._

  test("default is valid") {
    Given("a default readiness check instance")
    val rc = ReadinessCheck()

    Then("validation succeeds")
    validate(rc).isSuccess shouldBe true
  }

  test("empty name is invalid") {
    Given("a readiness check without a name")
    val rc = ReadinessCheck(name = "")

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("empty path is invalid") {
    Given("a readiness check with an empty path")
    val rc = ReadinessCheck(path = "")

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("empty portName is invalid") {
    Given("a readiness check with an empty portName")
    val rc = ReadinessCheck(portName = "")

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("unknown portName is invalid") {
    Given("a readiness check with an unknown portName")
    val rc = ReadinessCheck(portName = "unknown")

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("interval == 0 is invalid") {
    Given("a readiness check with a 0 interval")
    val rc = ReadinessCheck(interval = 0.seconds)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("interval < 0 is invalid") {
    Given("a readiness check with a negative interval")
    val rc = ReadinessCheck(interval = -10.seconds)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("timeout == 0 is invalid") {
    Given("a readiness check with a 0 timeout")
    val rc = ReadinessCheck(timeout = 0.seconds)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("timeout < 0 is invalid") {
    Given("a readiness check with a negative timeout")
    val rc = ReadinessCheck(interval = -10.seconds)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("timeout < interval is valid") {
    Given("a readiness check with a timeout which is smaller than the interval")
    val rc = ReadinessCheck(timeout = 3.seconds, interval = 10.seconds)

    Then("validation succeeds")
    validate(rc).isSuccess shouldBe true
  }

  test("timeout == interval is invalid") {
    Given("a readiness check with a timeout which is smaller equal to the interval")
    val rc = ReadinessCheck(timeout = 3.seconds, interval = 3.seconds)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("timeout > interval is invalid") {
    Given("a readiness check with a timeout which is greater than the interval")
    val rc = ReadinessCheck(timeout = 10.seconds, interval = 3.seconds)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  test("empty httpStatusCodesForReady is invalid") {
    Given("a readiness check with no defined httpStatusCodesForReady")
    val rc = ReadinessCheck(httpStatusCodesForReady = Set.empty)

    Then("validation fails")
    validate(rc).isFailure shouldBe true
  }

  val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(
    Seq(
      PortDefinition(
        port = 123,
        name = Some(ReadinessCheck.DefaultPortName))))
  implicit val readinessCheckValidator = ReadinessCheck.readinessCheckValidator(app)
}
