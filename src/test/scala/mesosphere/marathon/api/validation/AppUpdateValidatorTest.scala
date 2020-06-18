package mesosphere.marathon
package api.validation

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.{AppNormalization, ValidationHelper}
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.raml.{App, AppUpdate, Raml}
import mesosphere.marathon.state.{AppDefinition, ResourceRole}
import org.scalatest.Matchers
import play.api.libs.json.Json

class AppUpdateValidatorTest extends UnitTest with Matchers {

  implicit val validAppDefinition = AppDefinition.validAppDefinition(Set.empty, ValidationHelper.roleSettings())(PluginManager.None)

  "validation for network type changes" should {
    // regression test for DCOS-10641
    "allow updating from HOST to USER network for an app using a Docker container" in {
      val originalApp = Json.parse("""
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
        """.stripMargin).as[App]

      val config = AppNormalization.Configuration(None, "mesos-bridge-name", Set(), ResourceRole.Unreserved, false)
      val appDef = Raml.fromRaml(
        AppNormalization
          .apply(config)
          .normalized(AppNormalization.forDeprecated(config).normalized(originalApp))
      )

      val appUpdate = AppNormalization
        .forUpdates(config)
        .normalized(AppNormalization.forDeprecatedUpdates(config).normalized(Json.parse("""
            |{
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
            |      "network": "USER"
            |    }
            |  },
            |  "upgradeStrategy": {
            |    "minimumHealthCapacity": 0.5,
            |    "maximumOverCapacity": 0
            |  },
            |  "portDefinitions": [],
            |  "ipAddress": {
            |    "networkName": "dcos"
            |  },
            |  "requirePorts": false
            |}
          """.stripMargin).as[AppUpdate]))

      assert(validate(Raml.fromRaml(Raml.fromRaml(appUpdate -> appDef))).isSuccess)
    }
  }

}
