package mesosphere.marathon.api.validation

import mesosphere.marathon.MarathonSpec
import com.wix.accord.validate
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.{ Container, PathId }
import org.scalatest.Matchers

import scala.collection.immutable.Seq

class AppUpdateValidatorTest extends MarathonSpec with Matchers {

  test("test that Docker container is validated") {
    val f = new Fixture
    val update = AppUpdate(
      id = Some(PathId("/test")),
      container = Some(f.invalidDockerContainer))
    assert(validate(update).isFailure)
  }

  test("test that AppC container is validated") {
    val f = new Fixture
    val update = AppUpdate(
      id = Some(PathId("/test")),
      container = Some(f.invalidAppCContainer))
    assert(validate(update).isFailure)
  }

  class Fixture {
    def invalidDockerContainer: Container = Container.Docker(
      portMappings = Some(Seq(
        Docker.PortMapping(-1, Some(-1), -1, "tcp") // Invalid (negative) port numbers
      ))
    )

    def invalidAppCContainer: Container = Container.MesosAppC(
      image = "anImage",
      id = Some("invalidID")
    )
  }

}
