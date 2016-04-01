package mesosphere.marathon.core.readiness

import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import spray.http.{ ContentTypes, StatusCodes }
import scala.concurrent.duration._

class ReadinessCheckResultTest extends FunSuite with GivenWhenThen with Matchers {
  test("response from check and ready result (preserveLastResponse = true)") {
    val f = new Fixture
    When("converting a ready result")
    val result = ReadinessCheckResult.forSpecAndResponse(f.check, f.readyResponse)
    Then("we get the expected result")
    result should equal(
      ReadinessCheckResult(
        f.check.checkName,
        f.check.taskId,
        ready = true,
        lastResponse = Some(f.readyResponse)
      )
    )
  }

  test("response from check and ready result (preserveLastResponse = false)") {
    val f = new Fixture
    When("converting a NOT ready result")
    val result = ReadinessCheckResult.forSpecAndResponse(f.check.copy(preserveLastResponse = false), f.readyResponse)
    Then("we get the expected result")
    result should equal(
      ReadinessCheckResult(
        f.check.checkName,
        f.check.taskId,
        ready = true,
        lastResponse = None
      )
    )
  }

  test("response from check and NOT ready result (preserveLastResponse = true)") {
    val f = new Fixture
    When("converting a NOT ready result")
    val result = ReadinessCheckResult.forSpecAndResponse(f.check, f.notReadyResponse)
    Then("we get the expected result")
    result should equal(
      ReadinessCheckResult(
        f.check.checkName,
        f.check.taskId,
        ready = false,
        lastResponse = Some(f.notReadyResponse)
      )
    )
  }

  test("response from check and NOT ready result (preserveLastResponse = false)") {
    val f = new Fixture
    When("converting a ready result")
    val result = ReadinessCheckResult.forSpecAndResponse(f.check.copy(preserveLastResponse = false), f.notReadyResponse)
    Then("we get the expected result")
    result should equal(
      ReadinessCheckResult(
        f.check.checkName,
        f.check.taskId,
        ready = false,
        lastResponse = None
      )
    )
  }
  class Fixture {
    val check = ReadinessCheckSpec(
      taskId = Task.Id.forApp(PathId("/test")),
      checkName = "testCheck",
      url = "http://sample.url:123",
      interval = 3.seconds,
      timeout = 1.second,
      httpStatusCodesForReady = Set(StatusCodes.OK.intValue),
      preserveLastResponse = true
    )

    val readyResponse = HttpResponse(
      status = 200,
      contentType = ContentTypes.`text/plain`.value,
      body = "Hi"
    )

    val notReadyResponse = HttpResponse(
      status = 503,
      contentType = ContentTypes.`text/plain`.value,
      body = "Hi"
    )
  }
}
