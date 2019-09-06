package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import play.api.libs.json.Json

class ExternalVolumesIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override val marathonArgs: Map[String, String] = Map(
    "enable_features" -> "external_volumes"
  )

  "ExternalVolume names with options" should {
    "be considered valid" in {
      Given("a new app")
      val app = Json.parse(appWithExtVol).as[raml.App]

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
    }
  }

  val appWithExtVol: String =
    """
      |{
      |  "id": "nginx-ucr",
      |  "container": {
      |    "type": "MESOS",
      |    "volumes": [
      |      {
      |        "external": {
      |          "size": 5,
      |          "name": "name=teamvolumename,repl=1,secure=true,secret_key=volume-secret-key-team",
      |          "provider": "dvdi",
      |          "options": {
      |            "dvdi/driver": "pxd",
      |            "dvdi/secure": "true",
      |            "dvdi/secret_key": "volume-secret-key-team",
      |            "dvdi/repl": "1",
      |            "dvdi/shared": "true"
      |          }
      |        },
      |        "mode": "RW",
      |        "containerPath": "/mnt/nginx"
      |      }
      |    ],
      |    "docker": {
      |      "image": "nginx",
      |      "forcePullImage": false,
      |      "parameters": []
      |    },
      |    "portMappings": [
      |      {
      |        "containerPort": 80,
      |        "hostPort": 0,
      |        "protocol": "tcp",
      |        "name": "web"
      |      }
      |    ]
      |  },
      |  "cpus": 0.1,
      |  "disk": 0,
      |  "instances": 1,
      |  "mem": 128,
      |  "gpus": 0,
      |  "networks": [
      |    {
      |      "mode": "container/bridge"
      |    }
      |  ],
      |  "requirePorts": false,
      |  "healthChecks": [],
      |  "fetch": [],
      |  "constraints": []
      |}
    """.stripMargin
}
