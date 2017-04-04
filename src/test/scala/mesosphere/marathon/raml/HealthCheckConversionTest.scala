package mesosphere.marathon
package raml

import mesosphere.FunTest
import mesosphere.marathon.core.health._

class HealthCheckConversionTest extends FunTest {

  test("A MarathonHttpHealthCheck is converted correctly") {
    Given("A MarathonHttpHealthCheck")
    val check = MarathonHttpHealthCheck()

    When("The check is converted")
    val raml = check.toRaml[AppHealthCheck]

    Then("The raml is correct")
    raml.protocol should be(AppHealthCheckProtocol.Http)
    raml.command should be(empty)
    raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
    raml.ignoreHttp1xx should be(Some(check.ignoreHttp1xx))
    raml.intervalSeconds should be(check.interval.toSeconds)
    raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
    raml.path should be(check.path)
    raml.port should be(check.port)
    raml.timeoutSeconds should be(check.timeout.toSeconds)
  }

  test("A MarathonTcpHealthCheck is converted correctly") {
    Given("A MarathonTcpHealthCheck")
    val check = MarathonTcpHealthCheck()

    When("The check is converted")
    val raml = check.toRaml[AppHealthCheck]

    Then("The raml is correct")
    raml.protocol should be(AppHealthCheckProtocol.Tcp)
    raml.command should be(empty)
    raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
    raml.ignoreHttp1xx should be(empty)
    raml.intervalSeconds should be(check.interval.toSeconds)
    raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
    raml.path should be(empty)
    raml.port should be(check.port)
    raml.timeoutSeconds should be(check.timeout.toSeconds)
  }

  test("A MesosCommandHealthCheck is converted correctly") {
    Given("A MesosCommandHealthCheck")
    val check = MesosCommandHealthCheck(command = state.Command("test"))

    When("The check is converted")
    val raml = check.toRaml[AppHealthCheck]

    Then("The raml is correct")
    raml.protocol should be(AppHealthCheckProtocol.Command)
    raml.command should be(Some(CommandCheck("test")))
    raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
    raml.ignoreHttp1xx should be(empty)
    raml.intervalSeconds should be(check.interval.toSeconds)
    raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
    raml.path should be(empty)
    raml.port should be(empty)
    raml.timeoutSeconds should be(check.timeout.toSeconds)
  }

  test("A MesosHttpHealthCheck is converted correctly") {
    Given("A MesosHttpHealthCheck")
    val check = MesosHttpHealthCheck()

    When("The check is converted")
    val raml = check.toRaml[AppHealthCheck]

    Then("The raml is correct")
    raml.protocol should be(AppHealthCheckProtocol.MesosHttp)
    raml.command should be(empty)
    raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
    raml.ignoreHttp1xx should be(empty)
    raml.intervalSeconds should be(check.interval.toSeconds)
    raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
    raml.path should be(check.path)
    raml.port should be(check.port)
    raml.timeoutSeconds should be(check.timeout.toSeconds)
  }

  test("A MesosTcpHealthCheck is converted correctly") {
    Given("A MesosTcpHealthCheck")
    val check = MesosTcpHealthCheck()

    When("The check is converted")
    val raml = check.toRaml[AppHealthCheck]

    Then("The raml is correct")
    raml.protocol should be(AppHealthCheckProtocol.MesosTcp)
    raml.command should be(empty)
    raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
    raml.ignoreHttp1xx should be(empty)
    raml.intervalSeconds should be(check.interval.toSeconds)
    raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
    raml.path should be(empty)
    raml.port should be(check.port)
    raml.timeoutSeconds should be(check.timeout.toSeconds)
  }

  test("A HealthCheck with HttpHealthCheck defined should convert to a MesosHealthCheck") {
    val check = HealthCheck(http = Some(HttpHealthCheck(endpoint = "localhost")))
    val core = Some(check.fromRaml).collect {
      case c: MesosHttpHealthCheck => c
    }.getOrElse(fail("expected MesosHttpHealthCheck"))
    core.protocol should be(Protos.HealthCheckDefinition.Protocol.MESOS_HTTP)
    core.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
    core.path should not be 'defined
    core.port should not be 'defined
    core.portIndex should be (Some(PortReference("localhost")))
  }
}
