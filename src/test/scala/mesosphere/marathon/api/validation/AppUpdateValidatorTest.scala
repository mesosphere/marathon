package mesosphere.marathon.api.validation

import mesosphere.marathon.MarathonSpec
import com.wix.accord.validate
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.state.{ Container, PathId }
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers

class AppUpdateValidatorTest extends MarathonSpec with Matchers {

  test("test that container is validated") {
    val f = new Fixture
    val update = AppUpdate(
      id = Some(PathId("/test")),
      container = Some(f.invalidDockerContainer))
    assert(validate(update).isFailure)
  }

  class Fixture {
    def invalidDockerContainer: Container = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = None
    )
  }

}
