package mesosphere.marathon.core.readiness

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinition }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class ReadinessCheckSpecTest extends FunSuite with Matchers with GivenWhenThen {
  import MarathonTestHelper.Implicits._

  test("readiness check specs for one task with dynamic ports and one readiness check") {
    val f = new Fixture

    Given("an app with a readiness check and a randomly assigned port")
    val app = f.appWithOneReadinessCheck
    And("a task with two host port")
    val task = f.taskWithPorts

    When("calculating the ReadinessCheckSpec")
    val specs = ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(app, task, task.launched.get)

    Then("we get one spec")
    specs should have size 1
    val spec = specs.head
    And("it has the correct url")
    spec.url should equal("http://host.some:80/")
    And("the rest of the fields are correct, too")
    spec should equal(
      ReadinessCheckExecutor.ReadinessCheckSpec(
        url = "http://host.some:80/",
        taskId = task.taskId,
        checkName = app.readinessChecks.head.name,
        interval = app.readinessChecks.head.interval,
        timeout = app.readinessChecks.head.timeout,
        httpStatusCodesForReady = app.readinessChecks.head.httpStatusCodesForReady,
        preserveLastResponse = app.readinessChecks.head.preserveLastResponse
      )
    )
  }

  test("readiness check specs for one task with a required port and one readiness check") {
    val f = new Fixture

    Given("an app with a readiness check and a fixed port assignment")
    val app = f.appWithOneReadinessCheckWithRequiredPorts
    And("a task with two host ports")
    val task = f.taskWithPorts

    When("calculating the ReadinessCheckSpec")
    val specs = ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(app, task, task.launched.get)

    Then("we get one spec")
    specs should have size 1
    val spec = specs.head
    And("it has the correct url")
    spec.url should equal("http://host.some:80/")
    And("the rest of the fields are correct, too")
    spec should equal(
      ReadinessCheckExecutor.ReadinessCheckSpec(
        url = "http://host.some:80/",
        taskId = task.taskId,
        checkName = app.readinessChecks.head.name,
        interval = app.readinessChecks.head.interval,
        timeout = app.readinessChecks.head.timeout,
        httpStatusCodesForReady = app.readinessChecks.head.httpStatusCodesForReady,
        preserveLastResponse = app.readinessChecks.head.preserveLastResponse
      )
    )
  }

  test("multiple readiness check specs") {
    val f = new Fixture

    Given("an app with two readiness checks and randomly assigned ports")
    val app = f.appWithMultipleReadinessChecks
    And("a task with two host port")
    val task = f.taskWithPorts

    When("calculating the ReadinessCheckSpec")
    val specs = ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(app, task, task.launched.get)

    Then("we get two specs in the right order")
    specs should have size 2
    val specDefaultHttp = specs.head
    val specAlternative = specs(1)
    specDefaultHttp.checkName should equal(app.readinessChecks.head.name)
    specAlternative.checkName should equal(app.readinessChecks(1).name)

    And("the default http spec has the right url")
    specDefaultHttp.url should equal("http://host.some:81/")

    And("the alternative https spec has the right url")
    specAlternative.url should equal("https://host.some:80/v1/plan")
  }

  class Fixture {
    val appId: PathId = PathId("/test")

    val appWithOneReadinessCheck = AppDefinition(
      id = appId,
      readinessChecks = Seq(ReadinessCheckTestHelper.defaultHttp),
      portDefinitions = Seq(
        PortDefinition(
          port = AppDefinition.RandomPortValue,
          name = Some("http-api")
        )
      )
    )

    val appWithOneReadinessCheckWithRequiredPorts = AppDefinition(
      id = appId,
      readinessChecks = Seq(ReadinessCheckTestHelper.defaultHttp),
      requirePorts = true,
      portDefinitions = Seq(
        PortDefinition(
          port = 80,
          name = Some(ReadinessCheckTestHelper.defaultHttp.portName)
        ),
        PortDefinition(
          port = 81,
          name = Some("foo")
        )
      )
    )

    val appWithMultipleReadinessChecks = AppDefinition(
      id = appId,
      readinessChecks = Seq(
        ReadinessCheckTestHelper.defaultHttp,
        ReadinessCheckTestHelper.alternativeHttps
      ),
      portDefinitions = Seq(
        PortDefinition(
          port = 1, // service ports, ignore
          name = Some(ReadinessCheckTestHelper.alternativeHttps.portName)
        ),
        PortDefinition(
          port = 2, // service ports, ignore
          name = Some(ReadinessCheckTestHelper.defaultHttp.portName)
        )
      )
    )

    val taskWithPorts = MarathonTestHelper.runningTaskForApp(appId).withHostPorts(Seq(80, 81))
  }
}
