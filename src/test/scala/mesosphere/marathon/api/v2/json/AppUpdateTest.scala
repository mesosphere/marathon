package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state.DiscoveryInfo.Port
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._

import mesosphere.marathon.api.v2.Validation._

class AppUpdateTest extends MarathonSpec {
  import Formats._
  import mesosphere.marathon.integration.setup.V2TestFormats._

  test("Validation") {
    def shouldViolate(update: AppUpdate, path: String, template: String): Unit = {
      val violations = validate(update)
      assert(violations.isFailure)
      assert(ValidationHelper.getAllRuleConstrains(violations).exists(v =>
        v.property.getOrElse(false) == path && v.message == template
      ))
    }

    def shouldNotViolate(update: AppUpdate, path: String, template: String): Unit = {
      val violations = validate(update)
      assert(!ValidationHelper.getAllRuleConstrains(violations).exists(v =>
        v.property.getOrElse(false) == path && v.message == template))
    }

    val update = AppUpdate()

    shouldViolate(
      update.copy(ports = Some(Seq(9000, 8080, 9000))),
      "ports",
      "Elements must be unique"
    )

    shouldNotViolate(
      update.copy(ports = Some(Seq(AppDefinition.RandomPortValue, 8080, AppDefinition.RandomPortValue))),
      "ports",
      "Elements must be unique"
    )
  }

  private[this] def fromJsonString(json: String): AppUpdate = {
    Json.fromJson[AppUpdate](Json.parse(json)).get
  }

  test("SerializationRoundtrip for empty definition") {
    val update0 = AppUpdate(container = Some(Container.Empty))
    JsonTestHelper.assertSerializationRoundtripWorks(update0)
  }

  test("SerializationRoundtrip for extended definition") {
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
      uris = Some(Seq("http://dl.corp.org/prodX-1.2.3.tgz")),
      ports = Some(Seq(0, 0)),
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
}
