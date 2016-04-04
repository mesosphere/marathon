package mesosphere.marathon.state

import com.wix.accord._
import mesosphere.marathon.state.Container.Docker.PortMapping
import org.scalatest.{ FunSuiteLike, Matchers }

class PortMappingTest extends FunSuiteLike with Matchers {
  import mesosphere.marathon.state.Container.Docker.PortMapping.portMappingValidator

  test("valid portMapping should be valid") {
    validate(Fixture.validPortMapping) should be(Success)
  }

  test("valid portMapping with no name should be valid") {
    validate(Fixture.validPortMapping.copy(name = None)) should be(Success)
  }

  test("portMapping with invalid name should be invalid") {
    validate(Fixture.validPortMapping.copy(name = Some("!@?"))).isFailure should be(true)
  }

  test("portMapping with invalid protocol is invalid") {
    validate(Fixture.validPortMapping.copy(protocol = "icmp")).isFailure should be(true)
  }

  test("portMapping with invalid port is invalid") {
    validate(Fixture.validPortMapping.copy(hostPort = -1)).isFailure should be(true)
    validate(Fixture.validPortMapping.copy(containerPort = -1)).isFailure should be(true)
  }

  object Fixture {
    val validPortMapping = PortMapping(
      hostPort = 80,
      containerPort = 80,
      protocol = "tcp",
      name = Some("http-port"),
      labels = Map("foo" -> "bar"))
  }
}

