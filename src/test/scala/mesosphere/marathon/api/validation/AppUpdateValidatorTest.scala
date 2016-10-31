package mesosphere.marathon
package api.validation

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state.{ AppDefinition, Container, PathId }
import org.scalatest.Matchers
import play.api.libs.json.Json

class AppUpdateValidatorTest extends UnitTest with Matchers {
  implicit val appUpdateValidator = AppUpdate.appUpdateValidator(Set())
  implicit val validAppDefinition = AppDefinition.validAppDefinition(Set.empty)(PluginManager.None)

  "validation that considers container types" should {
    "test that Docker container is validated" in {
      val f = new Fixture
      val update = AppUpdate(
        id = Some(PathId("/test")),
        container = Some(f.invalidDockerContainer))
      assert(validate(update).isFailure)
    }

    "test that AppC container is validated" in {
      val f = new Fixture
      val update = AppUpdate(
        id = Some(PathId("/test")),
        container = Some(f.invalidAppCContainer))
      assert(validate(update).isFailure)
    }
  }

  "validation for network type changes" should {
    // regression test for DCOS-10641
    "allow updating from HOST to USER network for an app using a Docker container" in {
      val originalApp = Json.parse(
        """
          | {
          |  "id": "/sleepy-moby",
          |  "cmd": "sleep 1000",
          |  "instances": 1,
          |  "cpus": 1,
          |  "mem": 128,
          |  "disk": 0,
          |  "gpus": 0,
          |  "backoffSeconds": 1,
          |  "backoffFactor": 1.15,
          |  "maxLaunchDelaySeconds": 3600,
          |  "container": {
          |    "docker": {
          |      "image": "alpine",
          |      "forcePullImage": false,
          |      "privileged": false,
          |      "network": "HOST"
          |    }
          |  },
          |  "upgradeStrategy": {
          |    "minimumHealthCapacity": 0.5,
          |    "maximumOverCapacity": 0
          |  },
          |  "portDefinitions": [
          |    {
          |      "protocol": "tcp",
          |      "port": 10004
          |    }
          |  ],
          |  "requirePorts": false
          |}
        """.stripMargin).as[AppDefinition]

      val appUpdate = Json.parse(
        """
          | {
          |	"id": "/sleepy-moby",
          |	"cmd": "sleep 1000",
          |	"instances": 1,
          |	"cpus": 1,
          |	"mem": 128,
          |	"disk": 0,
          |	"gpus": 0,
          |	"backoffSeconds": 1,
          |	"backoffFactor": 1.15,
          |	"maxLaunchDelaySeconds": 3600,
          |	"container": {
          |		"docker": {
          |			"image": "alpine",
          |			"forcePullImage": false,
          |			"privileged": false,
          |			"network": "USER"
          |		}
          |	},
          |	"upgradeStrategy": {
          |		"minimumHealthCapacity": 0.5,
          |		"maximumOverCapacity": 0
          |	},
          | "portDefinitions": [],
          |	"ipAddress": {
          |		"networkName": "dcos"
          |	},
          |	"requirePorts": false
          |}
        """.stripMargin).as[AppUpdate]

      assert(validate(appUpdate(originalApp)).isSuccess)
    }
  }

  class Fixture {
    def invalidDockerContainer: Container = Container.Docker(
      portMappings = Seq(
        PortMapping(-1, Some(-1), -1, "tcp") // Invalid (negative) port numbers
      )
    )

    def invalidAppCContainer: Container = Container.MesosAppC(
      image = "anImage",
      id = Some("invalidID")
    )
  }

}
