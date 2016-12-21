package mesosphere.marathon
package raml

import mesosphere.FunTest

class ReadinessConversionsTest extends FunTest {

  test("A readiness check can be converted") {
    Given("A readiness check")
    val check = core.readiness.ReadinessCheck()

    When("The check is converted")
    val raml = check.toRaml[ReadinessCheck]

    Then("The check is converted correctly")
    raml.httpStatusCodesForReady should contain theSameElementsAs check.httpStatusCodesForReady
    raml.intervalSeconds should be(check.interval.toSeconds)
    raml.name should be(check.name)
    raml.path should be(check.path)
    raml.portName should be(check.portName)
    raml.preserveLastResponse should be(check.preserveLastResponse)
    raml.timeoutSeconds should be(check.timeout.toSeconds)
  }
}
