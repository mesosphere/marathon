package mesosphere.marathon.api.v2.json

import javax.validation.Validation

import mesosphere.marathon.{ MarathonSpec, Protos }
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.ModelValidation
import mesosphere.marathon.health.{ HealthCheck, HealthCounts }
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class V2AppDefinitionTest extends MarathonSpec with Matchers {

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

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

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val app1 = V2AppDefinition()
    assert(app1.cmd.isEmpty)
    assert(app1.args.isEmpty)
    val json1 = mapper.writeValueAsString(app1)
    val readResult1 = mapper.readValue(json1, classOf[V2AppDefinition])
    assert(readResult1 == app1)

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
    val readResult2 = mapper.readValue(json2, classOf[V2AppDefinition])
    assert(readResult2.healthChecks.head.command.isDefined)

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
    val json3 = mapper.writeValueAsString(app3)
    val readResult3 = mapper.readValue(json3, classOf[V2AppDefinition])
    assert(readResult3 == app3)

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
    val readResult4 = mapper.readValue(json4, classOf[V2AppDefinition])
    assert(readResult4.copy(version = app4.version) == app4)
  }

  test("jackson and play-json parsing has the same result") {
    val fullAppJson =
      """{
        "id": "/product/service/my-app",
        "cmd": "env && sleep 300",
        "args": ["/bin/sh", "-c", "env && sleep 300"],
        "container": {
            "type": "DOCKER",
            "docker": {
                "image": "group/image",
                "network": "BRIDGE",
                "portMappings": [
                    {
                        "containerPort": 8080,
                        "hostPort": 0,
                        "servicePort": 9000,
                        "protocol": "tcp"
                    },
                    {
                        "containerPort": 161,
                        "hostPort": 0,
                        "protocol": "udp"
                    }
                ]
            },
            "volumes": [
                {
                    "containerPath": "/etc/a",
                    "hostPath": "/var/data/a",
                    "mode": "RO"
                },
                {
                    "containerPath": "/etc/b",
                    "hostPath": "/var/data/b",
                    "mode": "RW"
                }
            ]
        },
        "cpus": 1.5,
        "mem": 256.0,
        "deployments": [
            {
                "id": "5cd987cd-85ae-4e70-8df7-f1438367d9cb"
            }
        ],
        "env": {
            "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
        },
        "executor": "",
        "constraints": [
            ["attribute", "GROUP_BY", "value"]
        ],
        "healthChecks": [
            {
                "protocol": "HTTP",
                "path": "/health",
                "gracePeriodSeconds": 3,
                "intervalSeconds": 10,
                "portIndex": 0,
                "timeoutSeconds": 10,
                "maxConsecutiveFailures": 3
            },
            {
                "protocol": "TCP",
                "gracePeriodSeconds": 3,
                "intervalSeconds": 5,
                "portIndex": 1,
                "timeoutSeconds": 5,
                "maxConsecutiveFailures": 3
            },
            {
                "protocol": "COMMAND",
                "command": { "value": "curl -f -X GET http://$HOST:$PORT0/health" },
                "maxConsecutiveFailures": 3
            }
        ],
        "instances": 3,
        "labels": {
          "one": "aaa",
          "two": "bbb",
          "three": "ccc"
        },
        "ports": [
            8080,
            9000
        ],
        "backoffSeconds": 1,
        "backoffFactor": 1.15,
        "maxLaunchDelaySeconds": 300,
        "tasksRunning": 3,
        "tasksStaged": 0,
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ],
        "dependencies": ["/product/db/mongo", "/product/db", "../../db"],
        "upgradeStrategy": {
            "minimumHealthCapacity": 0.5,
            "maximumOverCapacity": 0.5
        },
        "version": "2014-03-01T23:29:30.158Z"
    }"""

    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.Formats._

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    assert(mapper.readValue(fullAppJson, classOf[V2AppDefinition]) == Json.parse(fullAppJson).as[V2AppDefinition])
  }

  test("V2AppDefinition.WithTaskCountsAndDeploymentsWrites output of play-json matches jackson") {
    val app = V2AppDefinition()

    val task = Protos.MarathonTask
      .newBuilder
      .setHost("localhost")
      .setId("my-task")
      .addPorts(9999)
      .setStagedAt(0)
      .setStartedAt(0)
      .setVersion("some-version")
      .build()

    val appGroup = V2Group(PathId("/foo"), Set(app))

    val enrichedApp = app.withTaskCountsAndDeployments(
      Seq(EnrichedTask(app.id, task, Nil, Nil)),
      HealthCounts(0, 0, 0),
      Seq(DeploymentPlan(Group.empty, appGroup.toGroup)))

    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.Formats.WithTaskCountsAndDeploymentsWrites

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val playRes = Json.parse(Json.toJson(enrichedApp).toString())
    val jacksonRes = Json.parse(mapper.writeValueAsString(enrichedApp))
    assert(playRes == jacksonRes)
  }

  test("V2AppDefinition.WithTasksAndDeploymentsWrites output of play-json matches jackson") {
    val app = V2AppDefinition()

    val task = Protos.MarathonTask
      .newBuilder
      .setHost("localhost")
      .setId("my-task")
      .setSlaveId(SlaveID("0000-0000-0000"))
      .addPorts(9999)
      .setStagedAt(0)
      .setStartedAt(0)
      .setVersion("some-version")
      .build()

    val appGroup = V2Group(PathId("/foo"), Set(app))

    val enrichedApp = app.withTasksAndDeployments(
      Seq(EnrichedTask(app.id, task, Nil, Nil)),
      HealthCounts(0, 0, 0),
      Seq(DeploymentPlan(Group.empty, appGroup.toGroup)))

    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.Formats.WithTasksAndDeploymentsWrites

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val playRes = Json.parse(Json.toJson(enrichedApp).toString())
    val jacksonRes = Json.parse(mapper.writeValueAsString(enrichedApp))
    assert(playRes == jacksonRes)
  }

  test("V2AppDefinition.WithTasksAndDeploymentsAndFailuresWrites output of play-json matches jackson") {
    val app = V2AppDefinition()

    val task = Protos.MarathonTask
      .newBuilder
      .setHost("localhost")
      .setId("my-task")
      .setSlaveId(SlaveID("0000-0000-0000"))
      .addPorts(9999)
      .setStagedAt(0)
      .setStartedAt(0)
      .setVersion("some-version")
      .build()

    val appGroup = V2Group(PathId("/foo"), Set(app))

    val failure = TaskFailure(
      app.id,
      mesos.TaskID.newBuilder.setValue(task.getId).build(),
      mesos.TaskState.TASK_FAILED
    )

    val enrichedApp = app.withTasksAndDeploymentsAndFailures(
      Seq(EnrichedTask(app.id, task, Nil, Nil)),
      HealthCounts(0, 0, 0),
      Seq(DeploymentPlan(Group.empty, appGroup.toGroup)),
      Some(failure))

    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.Formats.WithTasksAndDeploymentsAndFailuresWrites

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val playRes = Json.parse(Json.toJson(enrichedApp).toString())
    val jacksonRes = Json.parse(mapper.writeValueAsString(enrichedApp))
    assert(playRes == jacksonRes)
  }

}
