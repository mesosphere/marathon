package mesosphere.marathon
package state

import com.wix.accord._
import mesosphere.UnitTest

class PortDefinitionTest extends UnitTest {
  "PortDefinitions" should {
    "valid portDefinition should be valid" in {
      validate(Fixture.validPortDefinition) should be(Success)
    }

    "portDefinition with tcp and udp should be valid" in {
      validate(Fixture.tcpUdpPortDefinition) should be(Success)
    }

    "valid portDefinition with no name should be valid" in {
      validate(Fixture.validPortDefinition.copy(name = None)) should be(Success)
    }

    "portDefinition with invalid protocol is invalid" in {
      validate(Fixture.validPortDefinition.copy(protocol = "icmp")).isFailure should be(true)
    }

    "portDefinition with invalid port is invalid" in {
      validate(Fixture.validPortDefinition.copy(port = -1)).isFailure should be(true)
    }
  }

  object Fixture {
    val validPortDefinition = PortDefinition(
      port = 80,
      protocol = "tcp",
      name = Some("http-port"),
      labels = Map("foo" -> "bar"))

    val tcpUdpPortDefinition = PortDefinition(
      port = 80,
      protocol = "udp,tcp",
      name = Some("http-port"),
      labels = Map("foo" -> "bar"))
  }
}

