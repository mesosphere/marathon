package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.ModelValidation
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class V2AppDefinitionTest extends MarathonSpec with Matchers {

  test("Validation") {
    def shouldViolate(app: V2AppDefinition, path: String, template: String) = {
      val violations = ModelValidation.checkAppConstraints(app, PathId.empty)
      assert(
        violations.exists { v =>
          v.getPropertyPath.toString == path && v.getMessageTemplate == template
        },
        s"Violations:\n${violations.mkString}"
      )
    }

    def shouldNotViolate(app: V2AppDefinition, path: String, template: String) = {
      val violations = ModelValidation.checkAppConstraints(app, PathId.empty)
      assert(
        !violations.exists { v =>
          v.getPropertyPath.toString == path && v.getMessageTemplate == template
        },
        s"Violations:\n${violations.mkString}"
      )
    }

    var app = V2AppDefinition(id = "a b".toRootPath)
    val idError = "path contains invalid characters (allowed: lowercase letters, digits, hyphens, \".\", \"..\")"
    validateJsonSchema(app, false)
    shouldViolate(app, "id", idError)

    app = app.copy(id = "a#$%^&*b".toRootPath)
    validateJsonSchema(app, false)
    shouldViolate(app, "id", idError)

    app = app.copy(id = "-dash-disallowed-at-start".toRootPath)
    validateJsonSchema(app, false)
    shouldViolate(app, "id", idError)

    app = app.copy(id = "dash-disallowed-at-end-".toRootPath)
    validateJsonSchema(app, false)
    shouldViolate(app, "id", idError)

    app = app.copy(id = "uppercaseLettersNoGood".toRootPath)
    validateJsonSchema(app, false)
    shouldViolate(app, "id", idError)

    app = V2AppDefinition(id = "test".toPath, instances = -3, ports = Seq(9000, 8080, 9000))
    shouldViolate(
      app,
      "ports",
      "Elements must be unique"
    )
    validateJsonSchema(app, false)

    app = V2AppDefinition(id = "test".toPath, ports = Seq(0, 0, 8080))
    shouldNotViolate(
      app,
      "ports",
      "Elements must be unique"
    )
    validateJsonSchema(app, false)

    val correct = V2AppDefinition(id = "test".toPath)

    app = correct.copy(executor = "//cmd")
    shouldNotViolate(
      app,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(app)

    app = correct.copy(executor = "some/relative/path.mte")
    shouldNotViolate(
      app,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(app)

    app = correct.copy(executor = "/some/absolute/path")
    shouldNotViolate(
      app,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(app)

    app = correct.copy(executor = "")
    shouldNotViolate(
      app,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(app)

    app = correct.copy(executor = "/test/")
    shouldViolate(
      app,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(app, false)

    app = correct.copy(executor = "/test//path")
    shouldViolate(
      app,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(app, false)

    app = correct.copy(cmd = Some("command"), args = Some(Seq("a", "b", "c")))
    shouldViolate(
      app,
      "",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )
    validateJsonSchema(app, false)

    app = correct.copy(cmd = None, args = Some(Seq("a", "b", "c")))
    shouldNotViolate(
      app,
      "",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )
    validateJsonSchema(app)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(1.2))
    shouldViolate(
      app,
      "upgradeStrategy.minimumHealthCapacity",
      "is greater than 1"
    )
    validateJsonSchema(app, false)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, 1.2))
    shouldViolate(
      app,
      "upgradeStrategy.maximumOverCapacity",
      "is greater than 1"
    )
    validateJsonSchema(app, false)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(-1.2))
    shouldViolate(
      app,
      "upgradeStrategy.minimumHealthCapacity",
      "is less than 0"
    )
    validateJsonSchema(app, false)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, -1.2))
    shouldViolate(
      app,
      "upgradeStrategy.maximumOverCapacity",
      "is less than 0"
    )
    validateJsonSchema(app, false)

    app = correct.copy(
      container = Some(Container(
        docker = Some(Docker(
          network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq(
            Docker.PortMapping(8080, 0, 0, "tcp"),
            Docker.PortMapping(8081, 0, 0, "tcp")
          ))
        ))
      )),
      ports = Nil,
      healthChecks = Set(HealthCheck(portIndex = 1))
    )
    shouldNotViolate(
      app,
      "",
      "Health check port indices must address an element of the ports array or container port mappings."
    )
    validateJsonSchema(app, false) // missing image

    app = correct.copy(
      container = Some(Container(
        docker = Some(Docker(
          network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = None
        ))
      )),
      ports = Nil,
      healthChecks = Set(HealthCheck(protocol = Protocol.COMMAND))
    )
    shouldNotViolate(
      app,
      "",
      "Health check port indices must address an element of the ports array or container port mappings."
    )
    validateJsonSchema(app, false) // missing image
  }

  test("SerializationRoundtrip empty") {
    import Formats._
    val app1 = V2AppDefinition(id = PathId("/test"))
    assert(app1.cmd.isEmpty)
    assert(app1.args.isEmpty)
    JsonTestHelper.assertSerializationRoundtripWorks(app1)
  }

  private[this] def fromJson(json: String): V2AppDefinition = {
    import Formats._
    Json.fromJson[V2AppDefinition](Json.parse(json)).getOrElse(throw new RuntimeException(s"could not parse: $json"))
  }

  test("Reading app definition with command health check") {
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
    assert(readResult2.healthChecks.head.command.isDefined)
  }

  test("SerializationRoundtrip with complex example") {
    import Formats._

    val app3 = V2AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      user = Some("nobody"),
      env = Map("key1" -> "value1", "key2" -> "value2"),
      instances = 5,
      cpus = 5.0,
      mem = 55.0,
      disk = 550.0,
      executor = "",
      constraints = Set(
        Constraint.newBuilder
          .setField("attribute")
          .setOperator(Constraint.Operator.GROUP_BY)
          .setValue("value")
          .build
      ),
      uris = Seq("hdfs://path/to/resource.zip"),
      storeUrls = Seq("http://my.org.com/artifacts/foo.bar"),
      ports = Seq(9001, 9002),
      requirePorts = true,
      backoff = 5.seconds,
      backoffFactor = 1.5,
      maxLaunchDelay = 3.minutes,
      container = Some(
        Container(docker = Some(Container.Docker("group/image")))
      ),
      healthChecks = Set(HealthCheck()),
      dependencies = Set(PathId("/prod/product/backend")),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.75)
    )
    JsonTestHelper.assertSerializationRoundtripWorks(app3)
  }

  test("Read app with container definition and port mappings") {
    import java.lang.{ Integer => JInt }

    import mesosphere.marathon.state.Container.Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network

    val app4 = V2AppDefinition(
      id = "bridged-webapp".toPath,
      cmd = Some("python3 -m http.server 8080"),
      container = Some(Container(
        docker = Some(Docker(
          image = "python:3",
          network = Some(Network.BRIDGE),
          portMappings = Some(Seq(
            PortMapping(containerPort = 8080, hostPort = 0, servicePort = 9000, protocol = "tcp")
          ))
        ))
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
    assert(readResult4.copy(version = app4.version) == app4)
  }
}
