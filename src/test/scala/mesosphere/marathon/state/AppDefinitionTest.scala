package mesosphere.marathon.state

import mesosphere.marathon.api.ModelValidation
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.{ Constraint, ServiceDefinition }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._

import com.google.common.collect.Lists
import javax.validation.Validation
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._

class AppDefinitionTest extends MarathonSpec with Matchers with ModelValidation {

  test("ToProto") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4,
      mem = 256,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd"
    )

    val proto1 = app1.toProto
    assert("play" == proto1.getId)
    assert(proto1.getCmd.hasValue)
    assert(proto1.getCmd.getShell)
    assert("bash foo-*/start -Dhttp.port=$PORT" == proto1.getCmd.getValue)
    assert(5 == proto1.getInstances)
    assert(Lists.newArrayList(8080, 8081) == proto1.getPortsList)
    assert("//cmd" == proto1.getExecutor)
    assert(4 == getScalarResourceValue(proto1, "cpus"), 1e-6)
    assert(256 == getScalarResourceValue(proto1, "mem"), 1e-6)
    assert("bash foo-*/start -Dhttp.port=$PORT" == proto1.getCmd.getValue)
    assert(!proto1.hasContainer)

    val app2 = AppDefinition(
      id = "play".toPath,
      cmd = None,
      args = Some(Seq("a", "b", "c")),
      container = Some(
        Container(docker = Some(Container.Docker("group/image")))
      ),
      cpus = 4,
      mem = 256,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd"
    )

    val proto2 = app2.toProto
    assert("play" == proto2.getId)
    assert(!proto2.getCmd.hasValue)
    assert(!proto2.getCmd.getShell)
    assert(Seq("a", "b", "c") == proto2.getCmd.getArgumentsList.asScala)
    assert(5 == proto2.getInstances)
    assert(Lists.newArrayList(8080, 8081) == proto2.getPortsList)
    assert("//cmd" == proto2.getExecutor)
    assert(4 == getScalarResourceValue(proto2, "cpus"), 1e-6)
    assert(256 == getScalarResourceValue(proto2, "mem"), 1e-6)
    assert(proto2.hasContainer)
  }

  test("MergeFromProto") {
    val cmd = mesos.CommandInfo.newBuilder
      .setValue("bash foo-*/start -Dhttp.port=$PORT")

    val proto1 = ServiceDefinition.newBuilder
      .setId("play")
      .setCmd(cmd)
      .setInstances(3)
      .setExecutor("//cmd")
      .setVersion(Timestamp.now.toString)
      .build

    val app1 = AppDefinition().mergeFromProto(proto1)

    assert("play" == app1.id.toString)
    assert(3 == app1.instances)
    assert("//cmd" == app1.executor)
    assert(Some("bash foo-*/start -Dhttp.port=$PORT") == app1.cmd)
  }

  test("ProtoRoundtrip") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4,
      mem = 256,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd"
    )
    val result1 = AppDefinition().mergeFromProto(app1.toProto)
    assert(result1 == app1)

    val app2 = AppDefinition(cmd = None, args = Some(Seq("a", "b", "c")))
    val result2 = AppDefinition().mergeFromProto(app2.toProto)
    assert(result2 == app2)
  }

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(app: AppDefinition, path: String, template: String) = {
      val violations = checkApp(app, PathId.empty)
      assert(
        violations.exists { v =>
          v.getPropertyPath.toString == path && v.getMessageTemplate.toString == template
        },
        s"Violations:\n${violations.mkString}"
      )
    }

    def shouldNotViolate(app: AppDefinition, path: String, template: String) = {
      val violations = checkApp(app, PathId.empty)
      assert(
        !violations.exists { v =>
          v.getPropertyPath.toString == path && v.getMessageTemplate == template
        },
        s"Violations:\n${violations.mkString}"
      )
    }

    val idError = "contains invalid characters (allowed: [a-z0-9]* . and .. in path)"
    val app = AppDefinition(id = "a b".toRootPath)

    shouldViolate(app, "id", idError)
    shouldViolate(app.copy(id = "a#$%^&*b".toRootPath), "id", idError)
    shouldViolate(app.copy(id = "-dash-disallowed-at-start".toRootPath), "id", idError)
    shouldViolate(app.copy(id = "dash-disallowed-at-end-".toRootPath), "id", idError)
    shouldViolate(app.copy(id = "uppercaseLettersNoGood".toRootPath), "id", idError)
    shouldNotViolate(app.copy(id = "ab".toRootPath), "id", idError)

    shouldViolate(
      AppDefinition(id = "test".toPath, instances = -3, ports = Seq(9000, 8080, 9000)),
      "ports",
      "Elements must be unique"
    )

    shouldNotViolate(
      AppDefinition(id = "test".toPath, ports = Seq(0, 0, 8080)),
      "ports",
      "Elements must be unique"
    )

    val correct = AppDefinition(id = "test".toPath)

    shouldNotViolate(
      correct.copy(executor = "//cmd"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      correct.copy(executor = "some/relative/path.mte"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      correct.copy(executor = "/some/absolute/path"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      correct.copy(executor = ""),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldViolate(
      correct.copy(executor = "/test/"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldViolate(
      correct.copy(executor = "/test//path"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldViolate(
      correct.copy(cmd = Some("command"), args = Some(Seq("a", "b", "c"))),
      "",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )

    shouldNotViolate(
      correct.copy(cmd = None, args = Some(Seq("a", "b", "c"))),
      "",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )

    shouldNotViolate(
      correct.copy(
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
      ),
      "",
      "Health check port indices must address an element of the ports array or container port mappings."
    )

  }

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val app1 = AppDefinition()
    assert(app1.cmd.isEmpty)
    assert(app1.args.isEmpty)
    val json1 = mapper.writeValueAsString(app1)
    val readResult1 = mapper.readValue(json1, classOf[AppDefinition])
    assert(readResult1 == app1)

    val json2 = """
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
    val readResult2 = mapper.readValue(json2, classOf[AppDefinition])
    assert(readResult2.healthChecks.head.command.isDefined)

    val app3 = AppDefinition(
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
      container = Some(
        Container(docker = Some(Container.Docker("group/image")))
      ),
      healthChecks = Set(HealthCheck()),
      dependencies = Set(PathId("/prod/product/backend")),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.75)
    )
    val json3 = mapper.writeValueAsString(app3)
    val readResult3 = mapper.readValue(json3, classOf[AppDefinition])
    assert(readResult3 == app3)

    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    import mesosphere.marathon.state.Container.Docker.PortMapping
    import java.lang.{ Integer => JInt }

    val app4 = AppDefinition(
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

    val json4 = """
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
    val readResult4 = mapper.readValue(json4, classOf[AppDefinition])
    assert(readResult4.copy(version = app4.version) == app4)
  }

  def getScalarResourceValue(proto: ServiceDefinition, name: String) = {
    proto.getResourcesList.asScala
      .find(_.getName == name)
      .get.getScalar.getValue
  }
}
