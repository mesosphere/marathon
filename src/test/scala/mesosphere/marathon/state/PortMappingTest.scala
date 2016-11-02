package mesosphere.marathon
package state

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.state.Container.PortMapping

class PortMappingTest extends UnitTest {

  import mesosphere.marathon.state.Container.PortMapping.portMappingValidator

  "PortMappings" should {
    "valid portMapping should be valid" in {
      validate(Fixture.validPortMapping) should be(Success)
    }

    "valid portMapping with no name should be valid" in {
      validate(Fixture.validPortMapping.copy(name = None)) should be(Success)
    }

    "portMapping with invalid protocol is invalid" in {
      validate(Fixture.validPortMapping.copy(protocol = "icmp")).isFailure should be(true)
    }

    "portMapping with invalid port is invalid" in {
      validate(Fixture.validPortMapping.copy(hostPort = Some(-1))).isFailure should be(true)
      validate(Fixture.validPortMapping.copy(containerPort = -1)).isFailure should be(true)
    }

    "portMapping without hostport may be valid" in {
      validate(Fixture.validPortMapping.copy(hostPort = None)) should be(Success)
    }

    "portMapping with zero hostport is valid" in {
      validate(Fixture.validPortMapping.copy(hostPort = Some(0))) should be(Success)
    }
  }
  object Fixture {
    val validPortMapping = PortMapping(
      hostPort = Some(80),
      containerPort = 80,
      protocol = "tcp",
      name = Some("http-port"),
      labels = Map("foo" -> "bar"))
  }
}

