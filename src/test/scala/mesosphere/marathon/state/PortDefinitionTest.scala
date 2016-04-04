package mesosphere.marathon.state

import com.wix.accord._
import org.scalatest.{ FunSuiteLike, Matchers }

class PortDefinitionTest extends FunSuiteLike with Matchers {
  test("valid portDefinition should be valid") {
    validate(Fixture.validPortDefinition) should be(Success)
  }

  test("valid portDefinition with no name should be valid") {
    validate(Fixture.validPortDefinition.copy(name = None)) should be(Success)
  }

  test("portDefinition with invalid name should be invalid") {
    validate(Fixture.validPortDefinition.copy(name = Some("!@?"))).isFailure should be(true)
  }

  test("portDefinition with invalid protocol is invalid") {
    validate(Fixture.validPortDefinition.copy(protocol = "icmp")).isFailure should be(true)
  }

  test("portDefinition with invalid port is invalid") {
    validate(Fixture.validPortDefinition.copy(port = -1)).isFailure should be(true)
  }

  object Fixture {
    val validPortDefinition = PortDefinition(
      port = 80,
      protocol = "tcp",
      name = Some("http-port"),
      labels = Map("foo" -> "bar"))
  }
}

