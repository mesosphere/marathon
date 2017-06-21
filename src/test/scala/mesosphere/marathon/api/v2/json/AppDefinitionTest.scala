package mesosphere.marathon
package api.v2.json

import com.wix.accord._
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.{ AppNormalization, AppsResource }
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, MesosCommandHealthCheck, MesosHttpHealthCheck, PortReference }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.raml.{ Raml, Resources, SecretDef }
import mesosphere.marathon.state.Container.{ Docker, PortMapping }
import mesosphere.marathon.state.EnvVarValue._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.{ UnitTest, ValidationTestLike }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class AppDefinitionTest extends UnitTest with ValidationTestLike {
  val enabledFeatures = Set("secrets")
  val validAppDefinition = AppDefinition.validAppDefinition(enabledFeatures)(PluginManager.None)

  private[this] def appNormalization(app: raml.App): raml.App =
    AppsResource.appNormalization(
      AppsResource.NormalizationConfig(
        enabledFeatures, AppNormalization.Configuration(None, "mesos-bridge-name"))).normalized(app)

  private[this] def fromJson(json: String): AppDefinition = {
    val raw: raml.App = Json.parse(json).as[raml.App]
    Raml.fromRaml(appNormalization(raw))
  }

  "AppDefinition" should {
    "Validation" in {
      def shouldViolate(app: AppDefinition, path: String, template: String)(implicit validAppDef: Validator[AppDefinition] = validAppDefinition): Unit = {
        validate(app) should containViolation(path, template)
      }

      def shouldNotViolate(app: AppDefinition, path: String, template: String)(implicit validAppDef: Validator[AppDefinition] = validAppDefinition): Unit = {
        validate(app) shouldNot containViolation(path, template)
      }

      var app = AppDefinition(id = "a b".toRootPath)
      val idError = "must fully match regular expression '^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$'"
      MarathonTestHelper.validateJsonSchema(app, false)
      shouldViolate(app, "/id", idError)

      app = app.copy(id = "a#$%^&*b".toRootPath)
      MarathonTestHelper.validateJsonSchema(app, false)
      shouldViolate(app, "/id", idError)

      app = app.copy(id = "-dash-disallowed-at-start".toRootPath)
      MarathonTestHelper.validateJsonSchema(app, false)
      shouldViolate(app, "/id", idError)

      app = app.copy(id = "dash-disallowed-at-end-".toRootPath)
      MarathonTestHelper.validateJsonSchema(app, false)
      shouldViolate(app, "/id", idError)

      app = app.copy(id = "uppercaseLettersNoGood".toRootPath)
      MarathonTestHelper.validateJsonSchema(app, false)
      shouldViolate(app, "/id", idError)

      val correct = AppDefinition(id = "test".toRootPath)

      app = correct.copy(
        networks = Seq(ContainerNetwork("whatever")), container = Some(Docker(
          image = "mesosphere/marathon",

          portMappings = Seq(
            PortMapping(8080, None, 0, "tcp", Some("foo"))
          )
        )),
        portDefinitions = Nil)
      shouldNotViolate(
        app,
        "/container/portMappings(0)",
        "hostPort is required for BRIDGE mode."
      )

      val caught = intercept[IllegalArgumentException] {
        correct.copy(
          networks = Seq(BridgeNetwork()),
          container = Some(Docker(
            image = "mesosphere/marathon",
            portMappings = Seq(
              PortMapping(8080, None, 0, "tcp", Some("foo"))
            )
          )),
          portDefinitions = Nil)
      }
      caught.getMessage should include("bridge networking requires that every host-port in a port-mapping is non-empty (but may be zero)")

      app = correct.copy(executor = "//cmd")
      shouldNotViolate(
        app,
        "/executor",
        "{javax.validation.constraints.Pattern.message}"
      )
      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(executor = "some/relative/path.mte")
      shouldNotViolate(
        app,
        "/executor",
        "{javax.validation.constraints.Pattern.message}"
      )
      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(executor = "/some/absolute/path")
      shouldNotViolate(
        app,
        "/executor",
        "{javax.validation.constraints.Pattern.message}"
      )
      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(executor = "")
      shouldNotViolate(
        app,
        "/executor",
        "{javax.validation.constraints.Pattern.message}"
      )
      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(executor = "/test/")
      shouldViolate(
        app,
        "/executor",
        "must fully match regular expression '^(//cmd)|(/?[^/]+(/[^/]+)*)|$'"
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(executor = "/test//path")
      shouldViolate(
        app,
        "/executor",
        "must fully match regular expression '^(//cmd)|(/?[^/]+(/[^/]+)*)|$'"
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(cmd = Some("command"), args = Seq("a", "b", "c"))
      shouldViolate(
        app,
        "/",
        "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(cmd = None, args = Seq("a", "b", "c"))
      shouldNotViolate(
        app,
        "/",
        "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
      )
      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(upgradeStrategy = UpgradeStrategy(1.2))
      shouldViolate(
        app,
        "/upgradeStrategy/minimumHealthCapacity",
        "got 1.2, expected between 0.0 and 1.0"
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, 1.2))
      shouldViolate(
        app,
        "/upgradeStrategy/maximumOverCapacity",
        "got 1.2, expected between 0.0 and 1.0"
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(upgradeStrategy = UpgradeStrategy(-1.2))
      shouldViolate(
        app,
        "/upgradeStrategy/minimumHealthCapacity",
        "got -1.2, expected between 0.0 and 1.0"
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, -1.2))
      shouldViolate(
        app,
        "/upgradeStrategy/maximumOverCapacity",
        "got -1.2, expected between 0.0 and 1.0"
      )
      MarathonTestHelper.validateJsonSchema(app, false)

      app = correct.copy(
        networks = Seq(BridgeNetwork()), container = Some(Docker(

          portMappings = Seq(
            PortMapping(8080, Some(0), 0, "tcp"),
            PortMapping(8081, Some(0), 0, "tcp")
          )
        )),
        portDefinitions = Nil,
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(1))))
      )
      shouldNotViolate(
        app,
        "/healthChecks(0)",
        "Health check port indices must address an element of the ports array or container port mappings."
      )
      MarathonTestHelper.validateJsonSchema(app, false) // missing image

      app = correct.copy(
        networks = Seq(BridgeNetwork()), container = Some(Docker()),
        portDefinitions = Nil,
        healthChecks = Set(MarathonHttpHealthCheck(port = Some(80)))
      )
      shouldNotViolate(
        app,
        "/healthChecks(0)",
        "Health check port indices must address an element of the ports array or container port mappings."
      )
      MarathonTestHelper.validateJsonSchema(app, false) // missing image

      app = correct.copy(
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(1))))
      )
      shouldViolate(
        app,
        "/healthChecks(0)",
        "Health check port indices must address an element of the ports array or container port mappings."
      )

      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(
        fetch = Seq(FetchUri(uri = "http://example.com/valid"), FetchUri(uri = "d://\not-a-uri"))
      )

      shouldViolate(
        app,
        "/fetch(1)/uri",
        "URI has invalid syntax."
      )

      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(
        unreachableStrategy = UnreachableDisabled)

      MarathonTestHelper.validateJsonSchema(app)

      app = correct.copy(
        fetch = Seq(FetchUri(uri = "http://example.com/valid"), FetchUri(uri = "/root/file"))
      )

      shouldNotViolate(
        app,
        "/fetch(1)",
        "URI has invalid syntax."
      )

      shouldViolate(app.copy(resources = Resources(mem = -3.0)), "/mem", "got -3.0, expected 0.0 or more")
      shouldViolate(app.copy(resources = Resources(cpus = -3.0)), "/cpus", "got -3.0, expected 0.0 or more")
      shouldViolate(app.copy(resources = Resources(disk = -3.0)), "/disk", "got -3.0, expected 0.0 or more")
      shouldViolate(app.copy(resources = Resources(gpus = -3)), "/gpus", "got -3, expected 0 or more")
      shouldViolate(app.copy(instances = -3), "/instances", "got -3, expected 0 or more")

      shouldViolate(app.copy(resources = Resources(gpus = 1)), "/", "Feature gpu_resources is not enabled. Enable with --enable_features gpu_resources)")

      {
        implicit val appValidator = AppDefinition.validAppDefinition(Set("gpu_resources"))(PluginManager.None)
        shouldNotViolate(app.copy(resources = Resources(gpus = 1)), "/", "Feature gpu_resources is not enabled. Enable with --enable_features gpu_resources)")(appValidator)
      }

      app = correct.copy(
        resources = Resources(gpus = 1),
        container = Some(Container.Docker())
      )

      shouldViolate(app, "/", "GPU resources only work with the Mesos containerizer")

      app = correct.copy(
        resources = Resources(gpus = 1),
        container = Some(Container.Mesos())
      )

      shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")

      app = correct.copy(
        resources = Resources(gpus = 1),
        container = Some(Container.MesosDocker())
      )

      shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")

      app = correct.copy(
        resources = Resources(gpus = 1),
        container = Some(Container.MesosAppC())
      )

      shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")

      app = correct.copy(
        resources = Resources(gpus = 1),
        container = None
      )

      shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")
    }

    "SerializationRoundtrip empty" in {

      val app1 = raml.App(id = "/test", cmd = Some("foo"))
      assert(app1.args.isEmpty)
      JsonTestHelper.assertSerializationRoundtripWorks(app1, appNormalization)
    }

    "Reading app definition with command health check" in {
      val json2 =
        """
      {
        "id": "toggle",
        "cmd": "python toggle.py $PORT0",
        "cpus": 0.2,
        "disk": 0.0,
        "healthChecks": [
          {
            "protocol": "COMMAND",
            "command": { "value": "env && http http://$HOST:$PORT0/" }
          }
        ],
        "instances": 2,
        "mem": 32.0,
        "ports": [0],
        "uris": ["http://downloads.mesosphere.com/misc/toggle.tgz"]
      }
      """
      val readResult2 = fromJson(json2)
      assert(readResult2.healthChecks.nonEmpty, readResult2)
      assert(
        readResult2.healthChecks.head == MesosCommandHealthCheck(command = Command("env && http http://$HOST:$PORT0/")),
        readResult2)
    }

    "SerializationRoundtrip with complex example" in {
      val app3 = raml.App(
        id = "/prod/product/my-app",
        cmd = Some("sleep 30"),
        user = Some("nobody"),
        env = raml.Environment("key1" -> "value1", "key2" -> "value2"),
        instances = 5,
        cpus = 5.0,
        mem = 55.0,
        disk = 550.0,
        constraints = Set(Seq("attribute", "GROUP_BY", "1")),
        portDefinitions = Some(Seq(raml.PortDefinition(9001), raml.PortDefinition(9002))),
        requirePorts = true,
        backoffSeconds = 5,
        backoffFactor = 1.5,
        maxLaunchDelaySeconds = 180,
        container = Some(raml.Container(`type` = raml.EngineType.Docker, docker = Some(raml.DockerContainer(image = "group/image")))),
        healthChecks = Set(raml.AppHealthCheck(protocol = raml.AppHealthCheckProtocol.Http, portIndex = Some(0))),
        dependencies = Set("/prod/product/backend"),
        upgradeStrategy = Some(raml.UpgradeStrategy(minimumHealthCapacity = 0.75, maximumOverCapacity = 1.0))
      )
      withValidationClue {
        JsonTestHelper.assertSerializationRoundtripWorks(app3, appNormalization)
      }
    }

    "SerializationRoundtrip preserves portIndex" in {
      val app3 = raml.App(
        id = "/prod/product/frontend/my-app",
        cmd = Some("sleep 30"),
        portDefinitions = Some(raml.PortDefinitions(9001, 9002)),
        healthChecks = Set(raml.AppHealthCheck(protocol = raml.AppHealthCheckProtocol.Http, portIndex = Some(1)))
      )
      JsonTestHelper.assertSerializationRoundtripWorks(app3, appNormalization)
    }

    "Reading AppDefinition adds portIndex to a Marathon HTTP health check if the app has ports" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = PortDefinitions(9001, 9002),
        healthChecks = Set(MarathonHttpHealthCheck())
      )

      val json = Json.toJson(app).toString()
      val reread = fromJson(json)

      assert(reread.healthChecks.headOption.contains(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))), json)
    }

    "Reading AppDefinition does not add portIndex to a Marathon HTTP health check if the app doesn't have ports" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = Seq.empty,
        healthChecks = Set(MarathonHttpHealthCheck())
      )

      val json = Json.toJson(app).toString()
      val ex = intercept[ValidationFailedException] {
        fromJson(json)
      }
      ex.getMessage should include("Health check port indices must address an element of the ports array or container port mappings")
    }

    "Reading AppDefinition adds portIndex to a Marathon HTTP health check if it has at least one portMapping" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = Seq.empty,
        networks = Seq(ContainerNetwork("whatever")), container = Some(
          Docker(
            image = "foo",
            portMappings = Seq(Container.PortMapping(containerPort = 1))
          )
        ),
        healthChecks = Set(MarathonHttpHealthCheck())
      )

      val json = Json.toJson(app)
      val reread = fromJson(json.toString)
      reread.healthChecks.headOption should be(Some(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
    }

    "Reading AppDefinition does not add portIndex to a Marathon HTTP health check if it has no ports nor portMappings" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = Seq.empty,
        container = Some(Docker(image = "foo")),
        healthChecks = Set(MarathonHttpHealthCheck())
      )

      val json = Json.toJson(app)
      val ex = intercept[ValidationFailedException] {
        fromJson(json.toString) withClue (json)
      }
      ex.getMessage should include("Health check port indices must address an element of the ports array or container port mappings")
    }

    "Reading AppDefinition does not add portIndex to a Mesos HTTP health check if the app doesn't have ports" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = Seq.empty,
        healthChecks = Set(MesosHttpHealthCheck())
      )

      val json = Json.toJson(app)
      val reread = fromJson(json.toString)

      reread.healthChecks.headOption should be(Some(MesosHttpHealthCheck(portIndex = None)))
    }

    "Reading AppDefinition adds portIndex to a Mesos HTTP health check if it has at least one portMapping" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = Seq.empty,
        networks = Seq(ContainerNetwork("whatever")), container = Some(
          Docker(
            image = "abc",
            portMappings = Seq(Container.PortMapping(containerPort = 1))
          )
        ),
        healthChecks = Set(MesosHttpHealthCheck())
      )

      withValidationClue {
        val json = Json.toJson(app)
        val reread = fromJson(json.toString)

        reread.healthChecks.headOption should be(Some(MesosHttpHealthCheck(portIndex = Some(PortReference(0)))))
      }
    }

    "Reading AppDefinition does not add portIndex to a Mesos HTTP health check if it has no ports nor portMappings" in {
      import Formats._

      val app = AppDefinition(
        id = PathId("/prod/product/frontend/my-app"),
        cmd = Some("sleep 30"),
        portDefinitions = Seq.empty,
        container = Some(Docker(image = "foo")),
        healthChecks = Set(MesosHttpHealthCheck())
      )

      val json = Json.toJson(app)
      val reread = fromJson(json.toString)

      reread.healthChecks.headOption should be(Some(MesosHttpHealthCheck(portIndex = None)))
    }

    "Read app with container definition and port mappings" in {

      val app4 = AppDefinition(
        id = "bridged-webapp".toRootPath,
        cmd = Some("python3 -m http.server 8080"),
        networks = Seq(BridgeNetwork()), container = Some(Docker(
          image = "python:3",

          portMappings = Seq(
            PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
          )
        ))
      )

      val json4 =
        """
      {
        "id": "bridged-webapp",
        "cmd": "python3 -m http.server 8080",
        "container": {
          "type": "DOCKER",
          "docker": {
            "image": "python:3",
            "network": "BRIDGE",
            "portMappings": [
              { "containerPort": 8080, "hostPort": 0, "servicePort": 9000, "protocol": "tcp" }
            ]
          }
        }
      }
      """
      val readResult4 = fromJson(json4)

      assert(readResult4.copy(versionInfo = app4.versionInfo) == app4)
    }

    "Read app with fetch definition" in {

      val app = AppDefinition(
        id = "app-with-fetch".toRootPath,
        cmd = Some("brew update"),
        fetch = Seq(
          new FetchUri(uri = "http://example.com/file1", executable = false, extract = true, cache = true,
            outputFile = None),
          new FetchUri(uri = "http://example.com/file2", executable = true, extract = false, cache = false,
            outputFile = None)
        ),
        portDefinitions = Seq(state.PortDefinition(0, name = Some("default"))))

      val json =
        """
      {
        "id": "app-with-fetch",
        "cmd": "brew update",
        "fetch": [
          {
            "uri": "http://example.com/file1",
            "executable": false,
            "extract": true,
            "cache": true
          },
          {
            "uri": "http://example.com/file2",
            "executable": true,
            "extract": false,
            "cache": false
          }
        ]
      }
      """
      val readResult = fromJson(json)
      assert(readResult.copy(versionInfo = app.versionInfo) == app)
    }

    "Transfer uris to fetch" in {
      val json =
        """
      {
        "id": "app-with-fetch",
        "cmd": "brew update",
        "uris": ["http://example.com/file1.tar.gz", "http://example.com/file"]
      }
      """

      val app = fromJson(json)

      assert(app.fetch(0).uri == "http://example.com/file1.tar.gz")
      assert(app.fetch(0).extract)

      assert(app.fetch(1).uri == "http://example.com/file")
      assert(!app.fetch(1).extract)

    }

    "Serialize deserialize path with fetch" in {
      val app = AppDefinition(
        id = "app-with-fetch".toPath,
        cmd = Some("brew update"),
        fetch = Seq(
          new FetchUri(uri = "http://example.com/file1", executable = false, extract = true, cache = true,
            outputFile = None),
          new FetchUri(uri = "http://example.com/file2", executable = true, extract = false, cache = false,
            outputFile = None)
        )
      )

      val proto = app.toProto

      val deserializedApp = AppDefinition.fromProto(proto)

      assert(deserializedApp.fetch(0).uri == "http://example.com/file1")
      assert(deserializedApp.fetch(0).extract)
      assert(!deserializedApp.fetch(0).executable)
      assert(deserializedApp.fetch(0).cache)

      assert(deserializedApp.fetch(1).uri == "http://example.com/file2")
      assert(!deserializedApp.fetch(1).extract)
      assert(deserializedApp.fetch(1).executable)
      assert(!deserializedApp.fetch(1).cache)
    }

    "Read app with labeled virtual network and discovery info" in {
      val app = AppDefinition(
        id = "app-with-ip-address".toRootPath,
        cmd = Some("python3 -m http.server 8080"),
        networks = Seq(ContainerNetwork(
          name = "whatever",
          labels = Map(
            "foo" -> "bar",
            "baz" -> "buzz"
          )
        )),
        container = Some(Container.Mesos(
          portMappings = Seq(Container.PortMapping(name = Some("http"), containerPort = 80, protocol = "tcp")
          )
        )),
        backoffStrategy = BackoffStrategy(maxLaunchDelay = 3600.seconds)
      )

      val json =
        """
      {
        "id": "app-with-ip-address",
        "cmd": "python3 -m http.server 8080",
        "ipAddress": {
          "networkName": "whatever",
          "groups": ["a", "b", "c"],
          "labels": {
            "foo": "bar",
            "baz": "buzz"
          },
          "discovery": {
            "ports": [
              { "name": "http", "number": 80, "protocol": "tcp" }
            ]
          }
        },
        "maxLaunchDelaySeconds": 3600
      }
      """

      val readResult = fromJson(json)

      assert(readResult.copy(versionInfo = app.versionInfo) == app)
    }

    "Read app with ip address without discovery info" in {
      val app = AppDefinition(
        id = "app-with-ip-address".toRootPath,
        cmd = Some("python3 -m http.server 8080"),
        container = Some(state.Container.Mesos(portMappings = Seq(Container.PortMapping.defaultInstance))), portDefinitions = Nil,
        networks = Seq(ContainerNetwork(
          "whatever",
          labels = Map(
            "foo" -> "bar",
            "baz" -> "buzz"

          ))),
        backoffStrategy = BackoffStrategy(maxLaunchDelay = 3600.seconds)
      )

      val json =
        """
      {
        "id": "app-with-ip-address",
        "cmd": "python3 -m http.server 8080",
        "ipAddress": {
          "networkName": "whatever",
          "groups": ["a", "b", "c"],
          "labels": {
            "foo": "bar",
            "baz": "buzz"
          }
        }
      }
      """

      val readResult = fromJson(json)

      assert(readResult.copy(versionInfo = app.versionInfo) == app)
    }

    "Read app with ip address and an empty ports list" in {
      val app = AppDefinition(
        id = "app-with-network-isolation".toRootPath,
        cmd = Some("python3 -m http.server 8080"),
        container = Some(state.Container.Mesos(portMappings = Seq(Container.PortMapping.defaultInstance))),
        networks = Seq(ContainerNetwork("whatever"))
      )

      val json =
        """
      {
        "id": "app-with-network-isolation",
        "cmd": "python3 -m http.server 8080",
        "ports": [],
        "ipAddress": {"networkName": "whatever"}
      }
      """

      val readResult = fromJson(json)

      assert(readResult.copy(versionInfo = app.versionInfo) == app)
    }

    "App may not have non-empty ports and ipAddress" in {
      val json =
        """
      {
        "id": "app-with-network-isolation",
        "cmd": "python3 -m http.server 8080",
        "ports": [0],
        "ipAddress": {
          "networkName": "whatever",
          "groups": ["a", "b", "c"],
          "labels": {
            "foo": "bar",
            "baz": "buzz"
          }
        }
      }
      """

      a[ValidationFailedException] shouldBe thrownBy(fromJson(json))

    }

    "App may not have both uris and fetch" in {
      val json =
        """
      {
        "id": "app-with-network-isolation",
        "uris": ["http://example.com/file1.tar.gz"],
        "fetch": [{"uri": "http://example.com/file1.tar.gz"}]
      }
      """

      a[ValidationFailedException] shouldBe thrownBy(fromJson(json))

    }

    "Residency serialization (toProto) and deserialization (fromProto)" in {
      val app = AppDefinition(
        id = "/test".toRootPath,
        residency = Some(Residency(
          relaunchEscalationTimeoutSeconds = 3600,
          taskLostBehavior = Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER)))
      val proto = app.toProto

      proto.hasResidency shouldBe true
      proto.getResidency.getRelaunchEscalationTimeoutSeconds shouldBe 3600
      proto.getResidency.getTaskLostBehavior shouldBe Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER

      val appAgain = AppDefinition.fromProto(proto)
      appAgain.residency should not be empty
      appAgain.residency.get.relaunchEscalationTimeoutSeconds shouldBe 3600
      appAgain.residency.get.taskLostBehavior shouldBe Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER
    }

    "SerializationRoundtrip preserves secret references in environment variables" in {
      val app3 = raml.App(
        id = "/prod/product/frontend/my-app",
        cmd = Some("sleep 30"),
        env = Map[String, raml.EnvVarValueOrSecret](
          "FOO" -> raml.EnvVarValue("bar"),
          "QAZ" -> raml.EnvVarSecret("james")
        ),
        secrets = Map("james" -> SecretDef("somesource"))
      )
      JsonTestHelper.assertSerializationRoundtripWorks(app3, appNormalization)
    }

    "environment variables with secrets should parse" in {
      val json =
        """
      {
        "id": "app-with-network-isolation",
        "cmd": "python3 -m http.server 8080",
        "env": {
          "qwe": "rty",
          "ssh": { "secret": "psst" }
        },
        "secrets": { "psst": { "source": "abc" } }
      }
      """

      val result = fromJson(json)
      assert(result.env.equals(Map[String, EnvVarValue](
        "qwe" -> "rty".toEnvVar,
        "ssh" -> EnvVarSecretRef("psst")
      )), result.env)
    }

    "container port mappings when empty stays empty" in {
      val appDef = AppDefinition(id = PathId("/test"), container = Some(Docker()))
      val roundTripped = AppDefinition.fromProto(appDef.toProto)
      roundTripped should equal(appDef)
      roundTripped.container.map(_.portMappings) should equal(appDef.container.map(_.portMappings))
    }
  }
}
