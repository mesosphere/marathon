package mesosphere.marathon.core.group.impl

import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, Container, Group, PathId }
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import org.apache.mesos.Protos.ContainerInfo
import com.wix.accord._

class GroupValidationTest extends UnitTest {
  import PathId._

  def container(servicePorts: Int*): Option[Container] = {
    Some(
      Docker.withDefaultPortMappings(Nil, "abc",
        network = Some(ContainerInfo.DockerInfo.Network.BRIDGE),
        portMappings = Option(
          servicePorts.map(port => PortMapping(servicePort = port)).toVector
        )
      )
    )
  }

  implicit private def validator = Group.validRootGroup(None)

  "Groups" when {
    "validated" should {
      "fail if service ports overlap" in {
        val app1 = AppDefinition("/app1".toPath, container = container(1))
        val app2 = AppDefinition("/app2".toPath, container = container(1))
        val g = Group(PathId.empty, apps = Map(app1.id -> app1, app2.id -> app2))
        validate(g) should be('failure)
      }
      "succeed if service ports are randomly allocated" in {
        val app1 = AppDefinition("/app1".toPath, container = container(0))
        val app2 = AppDefinition("/app2".toPath, container = container(0))
        val g = Group(PathId.empty, apps = Map(app1.id -> app1, app2.id -> app2))
        validate(g) should be('success)
      }
    }
    // TODO: needs a lot more coverage: https://github.com/mesosphere/marathon/issues/4198
  }

}
