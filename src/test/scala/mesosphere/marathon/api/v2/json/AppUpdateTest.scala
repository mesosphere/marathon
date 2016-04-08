package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state.DiscoveryInfo.Port
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => mesos }
import play.api.data.validation.ValidationError
import play.api.libs.json.{ JsPath, JsError, Json }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

import com.wix.accord._

import scala.util.Try

class AppUpdateTest extends MarathonSpec {
  import Formats._
  import mesosphere.marathon.integration.setup.V2TestFormats._

  test("Validation") {
    def shouldViolate(update: AppUpdate, path: String, template: String): Unit = {
      val violations = validate(update)
      assert(violations.isFailure)
      assert(ValidationHelper.getAllRuleConstrains(violations).exists(v =>
        v.path.getOrElse(false) == path && v.message == template
      ))
    }

    def shouldNotViolate(update: AppUpdate, path: String, template: String): Unit = {
      val violations = validate(update)
      assert(!ValidationHelper.getAllRuleConstrains(violations).exists(v =>
        v.path.getOrElse(false) == path && v.message == template))
    }

    val update = AppUpdate()

    shouldViolate(
      update.copy(portDefinitions = Some(PortDefinitions(9000, 8080, 9000))),
      "/portDefinitions",
      "Ports must be unique."
    )

    shouldViolate(
      update.copy(portDefinitions = Some(Seq(
        PortDefinition(port = 9000, name = Some("foo")),
        PortDefinition(port = 9001, name = Some("foo"))))
      ),
      "/portDefinitions",
      "Port names must be unique."
    )

    shouldNotViolate(
      update.copy(portDefinitions = Some(Seq(
        PortDefinition(port = 9000, name = Some("foo")),
        PortDefinition(port = 9001, name = Some("bar"))))
      ),
      "/portDefinitions",
      "Port names must be unique."
    )

    shouldViolate(update.copy(mem = Some(-3.0)), "/mem", "got -3.0, expected 0.0 or more")
    shouldViolate(update.copy(cpus = Some(-3.0)), "/cpus", "got -3.0, expected 0.0 or more")
    shouldViolate(update.copy(disk = Some(-3.0)), "/disk", "got -3.0, expected 0.0 or more")
    shouldViolate(update.copy(instances = Some(-3)), "/instances", "got -3, expected 0 or more")
  }

  private[this] def fromJsonString(json: String): AppUpdate = {
    Json.fromJson[AppUpdate](Json.parse(json)).get
  }

  test("SerializationRoundtrip for empty definition") {
    val update0 = AppUpdate(container = Some(Container.Empty))
    JsonTestHelper.assertSerializationRoundtripWorks(update0)
  }

  ignore("SerializationRoundtrip for extended definition") {
    val update1 = AppUpdate(
      cmd = Some("sleep 60"),
      args = None,
      user = Some("nobody"),
      env = Some(Map("LANG" -> "en-US")),
      instances = Some(16),
      cpus = Some(2.0),
      mem = Some(256.0),
      disk = Some(1024.0),
      executor = Some("/opt/executors/bin/some.executor"),
      constraints = Some(Set()),
      fetch = Some(Seq(FetchUri(uri = "http://dl.corp.org/prodX-1.2.3.tgz"))),
      backoff = Some(2.seconds),
      backoffFactor = Some(1.2),
      maxLaunchDelay = Some(1.minutes),
      container = Some(
        Container(
          `type` = mesos.ContainerInfo.Type.DOCKER,
          volumes = Nil,
          docker = Some(Docker(image = "docker:///group/image"))
        )
      ),
      healthChecks = Some(Set[HealthCheck]()),
      dependencies = Some(Set[PathId]()),
      upgradeStrategy = Some(UpgradeStrategy.empty),
      labels = Some(
        Map(
          "one" -> "aaa",
          "two" -> "bbb",
          "three" -> "ccc"
        )
      ),
      ipAddress = Some(IpAddress(
        groups = Seq("a", "b", "c"),
        labels = Map(
          "foo" -> "bar",
          "baz" -> "buzz"
        ),
        discoveryInfo = DiscoveryInfo(
          ports = Seq(Port(name = "http", number = 80, protocol = "tcp"))
        )
      ))
    )
    JsonTestHelper.assertSerializationRoundtripWorks(update1)
  }

  test("Serialization result of empty container") {
    val update2 = AppUpdate(container = None)
    val json2 =
      """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "dependencies": null,
        "version": null
      }
    """
    val readResult2 = fromJsonString(json2)
    assert(readResult2 == update2)
  }

  test("Serialization result of empty ipAddress") {
    val update2 = AppUpdate(ipAddress = None)
    val json2 =
      """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "dependencies": null,
        "ipAddress": null,
        "version": null
      }
      """
    val readResult2 = fromJsonString(json2)
    assert(readResult2 == update2)
  }

  test("Empty json corresponds to default instance") {
    val update3 = AppUpdate()
    val json3 = "{}"
    val readResult3 = fromJsonString(json3)
    assert(readResult3 == update3)
  }

  test("Args are correctly read") {
    val update4 = AppUpdate(args = Some(Seq("a", "b", "c")))
    val json4 = """{ "args": ["a", "b", "c"] }"""
    val readResult4 = fromJsonString(json4)
    assert(readResult4 == update4)
  }

  test("'version' field can only be combined with 'id'") {
    assert(AppUpdate(version = Some(Timestamp.now())).onlyVersionOrIdSet)

    assert(AppUpdate(id = Some("foo".toPath), version = Some(Timestamp.now())).onlyVersionOrIdSet)

    intercept[Exception] {
      AppUpdate(cmd = Some("foo"), version = Some(Timestamp.now()))
    }
  }

  test("acceptedResourceRoles of update is only applied when != None") {
    val app = AppDefinition(id = PathId("withAcceptedRoles"), acceptedResourceRoles = Some(Set("a")))

    val unchanged = AppUpdate().apply(app).copy(versionInfo = app.versionInfo)
    assert(unchanged == app)

    val changed = AppUpdate(acceptedResourceRoles = Some(Set("b"))).apply(app).copy(versionInfo = app.versionInfo)
    assert(changed == app.copy(acceptedResourceRoles = Some(Set("b"))))
  }

  test("AppUpdate does not change existing versionInfo") {
    val app = AppDefinition(
      id = PathId("test"),
      cmd = Some("sleep 1"),
      versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(1))
    )

    val updateCmd = AppUpdate(cmd = Some("sleep 2"))
    assert(updateCmd(app) == app)
  }

  test("AppUpdate with a version and other changes are not allowed") {
    val attempt = Try(AppUpdate(id = Some(PathId("/test")), cmd = Some("sleep 2"), version = Some(Timestamp(2))))
    assert(attempt.failed.get.getMessage.contains("The 'version' field may only be combined with the 'id' field."))
  }

  test("update may not have both uris and fetch") {
    val json =
      """
      {
        "id": "app-with-network-isolation",
        "uris": ["http://example.com/file1.tar.gz"],
        "fetch": [{"uri": "http://example.com/file1.tar.gz"}]
      }
      """

    import Formats._
    val result = Json.fromJson[AppUpdate](Json.parse(json))
    assert(result == JsError(ValidationError("You cannot specify both uris and fetch fields")))
  }

  test("update may not have both ports and portDefinitions") {
    val json =
      """
      {
        "id": "app",
        "ports": [1],
        "portDefinitions": [{"port": 2}]
      }
      """

    import Formats._
    val result = Json.fromJson[AppUpdate](Json.parse(json))
    assert(result == JsError(ValidationError("You cannot specify both ports and port definitions")))
  }

  test("update may not have duplicated ports") {
    val json =
      """
      {
        "id": "app",
        "ports": [1, 1]
      }
      """

    import Formats._
    val result = Json.fromJson[AppUpdate](Json.parse(json))
    assert(result == JsError(JsPath \ "ports", ValidationError("Ports must be unique.")))
  }

  test("update JSON serialization preserves readiness checks") {
    val update = AppUpdate(
      id = Some(PathId("/test")),
      readinessChecks = Some(Seq(ReadinessCheckTestHelper.alternativeHttps))
    )
    val json = Json.toJson(update)
    val reread = json.as[AppUpdate]
    assert(reread == update)
  }

  test("update readiness checks are applied to app") {
    val update = AppUpdate(
      id = Some(PathId("/test")),
      readinessChecks = Some(Seq(ReadinessCheckTestHelper.alternativeHttps))
    )
    val app = AppDefinition(id = PathId("/test"))
    val updated = update(app)

    assert(updated.readinessChecks == update.readinessChecks.get)
  }

  test("empty app updateStrategy on persistent volumes") {
    val json =
      """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "residency": {
          "relaunchEscalationTimeoutSeconds": 10,
          "taskLostBehavior": "WAIT_FOREVER"
        }
      }
      """

    val update = fromJsonString(json)
    val strategy = update.empty("foo".toPath).upgradeStrategy
    assert(strategy.minimumHealthCapacity == 0.5
      && strategy.maximumOverCapacity == 0)
  }

  test("empty app residency on persistent volumes") {
    val json =
      """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "upgradeStrategy": {
          "minimumHealthCapacity": 0.2,
          "maximumOverCapacity": 0
        }
      }
      """

    val update = fromJsonString(json)
    val residency = update.empty("foo".toPath).residency
    assert(residency.isDefined)
    assert(residency.forall(_ == Residency.defaultResidency))
  }

  test("empty app updateStrategy") {
    val json =
      """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "residency": {
          "relaunchEscalationTimeoutSeconds": 10,
          "taskLostBehavior": "WAIT_FOREVER"
        }
      }
      """

    val update = fromJsonString(json)
    val strategy = update.empty("foo".toPath).upgradeStrategy
    assert(strategy.minimumHealthCapacity == 0.5
      && strategy.maximumOverCapacity == 0)
  }

  test("empty app residency") {
    val json =
      """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "upgradeStrategy": {
          "minimumHealthCapacity": 0.2,
          "maximumOverCapacity": 0
        }
      }
      """

    val update = fromJsonString(json)
    val residency = update.empty("foo".toPath).residency
    assert(residency.isDefined)
    assert(residency.forall(_ == Residency.defaultResidency))
  }

  test("empty app update strategy on external volumes") {
    val json =
      """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "/docker_storage",
              "mode": "RW",
              "external": {
                "name": "my-external-volume",
                "provider": "dvdi",
                "size": 1234
                }
              }]
        }
      }
      """

    val update = fromJsonString(json)
    val strategy = update.empty("foo".toPath).upgradeStrategy
    assert(strategy == UpgradeStrategy.forResidentTasks)
  }
}
