package mesosphere.marathon.api.v2.json

import javax.validation.Validation

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state._
import mesosphere.marathon.state.PathId._
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._

class V2AppUpdateTest extends MarathonSpec {
  import Formats._
  import mesosphere.marathon.integration.setup.V2TestFormats._

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(update: V2AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assert(violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    def shouldNotViolate(update: V2AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assert(!violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    val update = V2AppUpdate()

    shouldViolate(
      update.copy(ports = Some(Seq(9000, 8080, 9000))),
      "ports",
      "Elements must be unique"
    )
  }

  private[this] def fromJsonString(json: String): V2AppUpdate = {
    Json.fromJson[V2AppUpdate](Json.parse(json)).get
  }

  test("SerializationRoundtrip for empty definition") {
    val update0 = V2AppUpdate(container = Some(Container.Empty))
    JsonTestHelper.assertSerializationRoundtripWorks(update0)
  }

  test("SerializationRoundtrip for extended definition") {
    val update1 = V2AppUpdate(
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
      )
    )
    JsonTestHelper.assertSerializationRoundtripWorks(update1)
  }

  test("Serialization result of empty container") {
    val update2 = V2AppUpdate(container = None)
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

  test("Empty json corresponds to default instance") {
    val update3 = V2AppUpdate()
    val json3 = "{}"
    val readResult3 = fromJsonString(json3)
    assert(readResult3 == update3)
  }

  test("Args are correctly read") {
    val update4 = V2AppUpdate(args = Some(Seq("a", "b", "c")))
    val json4 = """{ "args": ["a", "b", "c"] }"""
    val readResult4 = fromJsonString(json4)
    assert(readResult4 == update4)
  }

  test("'version' field can only be combined with 'id'") {
    assert(V2AppUpdate(version = Some(Timestamp.now())).onlyVersionOrIdSet)

    assert(V2AppUpdate(id = Some("foo".toPath), version = Some(Timestamp.now())).onlyVersionOrIdSet)

    intercept[Exception] {
      V2AppUpdate(cmd = Some("foo"), version = Some(Timestamp.now()))
    }
  }

  test("acceptedResourceRoles of update is only applied when != None") {
    val app = AppDefinition(id = PathId("withAcceptedRoles"), acceptedResourceRoles = Some(Set("a")))

    // TODO AW: is this correct?
    val unchanged = V2AppUpdate().apply(app).copy(versionInfo = app.versionInfo)
    assert(unchanged == app)

    val changed = V2AppUpdate(acceptedResourceRoles = Some(Set("b"))).apply(app).copy(versionInfo = app.versionInfo)
    assert(changed == app.copy(acceptedResourceRoles = Some(Set("b"))))
  }
}
