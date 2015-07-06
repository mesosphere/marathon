package mesosphere.marathon.state

import javax.validation.Validation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.google.common.collect.Lists
import mesosphere.jackson.CaseClassModule
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.{ Constraint, ServiceDefinition }
import mesosphere.marathon.api.ModelValidation
import mesosphere.marathon.api.v2.json.{ EnrichedTask, MarathonModule }
import mesosphere.marathon.health.{ HealthCheck, HealthCounts }
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonSpec, Protos }
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._

class AppDefinitionTest extends MarathonSpec with Matchers {

  test("ToProto") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd",
      acceptedResourceRoles = Some(Set("a", "b"))
    )

    validateJsonSchema(app1)
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
    assert(1.0 == proto1.getUpgradeStrategy.getMinimumHealthCapacity)
    assert(1.0 == proto1.getUpgradeStrategy.getMaximumOverCapacity)
    assert(proto1.hasAcceptedResourceRoles)
    assert(proto1.getAcceptedResourceRoles == Protos.ResourceRoles.newBuilder().addRole("a").addRole("b").build())

    val app2 = AppDefinition(
      id = "play".toPath,
      cmd = None,
      args = Some(Seq("a", "b", "c")),
      container = Some(
        Container(docker = Some(Container.Docker("group/image")))
      ),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd",
      upgradeStrategy = UpgradeStrategy(0.7, 0.4)
    )

    validateJsonSchema(app2)
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
    assert(0.7 == proto2.getUpgradeStrategy.getMinimumHealthCapacity)
    assert(0.4 == proto2.getUpgradeStrategy.getMaximumOverCapacity)
    assert(!proto2.hasAcceptedResourceRoles)
  }

  test("CMD to proto and back again") {
    val app = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT")
    )

    validateJsonSchema(app)
    val proto = app.toProto
    proto.getId should be("play")
    proto.getCmd.hasValue should be(true)
    proto.getCmd.getShell should be(true)
    proto.getCmd.getValue should be("bash foo-*/start -Dhttp.port=$PORT")

    val read = AppDefinition().mergeFromProto(proto)
    read should be(app)
  }

  test("ARGS to proto and back again") {
    val app = AppDefinition(
      id = "play".toPath,
      args = Some(Seq("bash", "foo-*/start", "-Dhttp.port=$PORT"))
    )

    validateJsonSchema(app)
    val proto = app.toProto
    proto.getId should be("play")
    proto.getCmd.hasValue should be(true)
    proto.getCmd.getShell should be(false)
    proto.getCmd.getValue should be("bash")
    proto.getCmd.getArgumentsList.asScala should be(Seq("bash", "foo-*/start", "-Dhttp.port=$PORT"))

    val read = AppDefinition().mergeFromProto(proto)
    read should be(app)
  }

  test("MergeFromProto") {
    val cmd = mesos.CommandInfo.newBuilder
      .setValue("bash foo-*/start -Dhttp.port=$PORT")

    val proto1 = ServiceDefinition.newBuilder
      .setId("play")
      .setCmd(cmd)
      .setInstances(3)
      .setExecutor("//cmd")
      .setVersion(Timestamp.now().toString)
      .build

    val app1 = AppDefinition().mergeFromProto(proto1)

    validateJsonSchema(app1)
    assert("play" == app1.id.toString)
    assert(3 == app1.instances)
    assert("//cmd" == app1.executor)
    assert(Some("bash foo-*/start -Dhttp.port=$PORT") == app1.cmd)
  }

  test("ProtoRoundtrip") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd",
      labels = Map(
        "one" -> "aaa",
        "two" -> "bbb",
        "three" -> "ccc"
      )
    )
    val result1 = AppDefinition().mergeFromProto(app1.toProto)
    assert(result1 == app1)
    validateJsonSchema(app1)

    val app2 = AppDefinition(cmd = None, args = Some(Seq("a", "b", "c")))
    val result2 = AppDefinition().mergeFromProto(app2.toProto)
    assert(result2 == app2)
    validateJsonSchema(app2, false) // no id
  }

  test("Validation: empty cmd should fail") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("")
    )
    validateJsonSchema(app1, valid = false)
  }

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(app: AppDefinition, path: String, template: String) = {
      val violations = ModelValidation.checkAppConstraints(app, PathId.empty)
      assert(
        violations.exists { v =>
          v.getPropertyPath.toString == path && v.getMessageTemplate == template
        },
        s"Violations:\n${violations.mkString}"
      )
    }

    def shouldNotViolate(app: AppDefinition, path: String, template: String) = {
      val violations = ModelValidation.checkAppConstraints(app, PathId.empty)
      assert(
        !violations.exists { v =>
          v.getPropertyPath.toString == path && v.getMessageTemplate == template
        },
        s"Violations:\n${violations.mkString}"
      )
    }

    val idError = "path contains invalid characters (allowed: lowercase letters, digits, hyphens, \".\", \"..\")"
    var app = AppDefinition(id = "a b".toRootPath)
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

    val app2 = AppDefinition(id = "test".toPath, instances = -3, ports = Seq(9000, 8080, 9000))
    shouldViolate(
      app2,
      "ports",
      "Elements must be unique"
    )
    validateJsonSchema(app2, false)

    val app3 = AppDefinition(id = "test".toPath, ports = Seq(0, 0, 8080))
    shouldNotViolate(
      app3,
      "ports",
      "Elements must be unique"
    )
    validateJsonSchema(app3, false)

    val correct = AppDefinition(id = "test".toPath)

    var appX = correct.copy(executor = "//cmd")
    shouldNotViolate(
      appX,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(appX)

    appX = correct.copy(executor = "some/relative/path.mte")
    shouldNotViolate(
      appX,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(appX)

    appX = correct.copy(executor = "/some/absolute/path")
    shouldNotViolate(
      appX,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(appX)

    appX = correct.copy(executor = "")
    shouldNotViolate(
      appX,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(appX)

    appX = correct.copy(executor = "/test/")
    shouldViolate(
      appX,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(appX, false)

    appX = correct.copy(executor = "/test//path")
    shouldViolate(
      appX,
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    validateJsonSchema(appX, false)

    appX = correct.copy(cmd = Some("command"), args = Some(Seq("a", "b", "c")))
    shouldViolate(
      appX,
      "",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )
    validateJsonSchema(appX, false)

    appX = correct.copy(cmd = None, args = Some(Seq("a", "b", "c")))
    shouldNotViolate(
      appX,
      "",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )
    validateJsonSchema(appX)

    appX = correct.copy(upgradeStrategy = UpgradeStrategy(1.2))
    shouldViolate(
      appX,
      "upgradeStrategy.minimumHealthCapacity",
      "is greater than 1"
    )
    validateJsonSchema(appX, false)

    appX = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, 1.2))
    shouldViolate(
      appX,
      "upgradeStrategy.maximumOverCapacity",
      "is greater than 1"
    )
    validateJsonSchema(appX, false)

    appX = correct.copy(upgradeStrategy = UpgradeStrategy(-1.2))
    shouldViolate(
      appX,
      "upgradeStrategy.minimumHealthCapacity",
      "is less than 0"
    )
    validateJsonSchema(appX, false)

    appX = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, -1.2))
    shouldViolate(
      appX,
      "upgradeStrategy.maximumOverCapacity",
      "is less than 0"
    )
    validateJsonSchema(appX, false)

    appX =
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
      )
    shouldNotViolate(
      appX,
      "",
      "Health check port indices must address an element of the ports array or container port mappings."
    )
    validateJsonSchema(appX, false) // missing image

    appX =
      correct.copy(
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
      appX,
      "",
      "Health check port indices must address an element of the ports array or container port mappings."
    )
    validateJsonSchema(appX, false) // missing image
  }

  test("SerializationRoundtrip") {

    val app1 = AppDefinition()
    assert(app1.cmd.isEmpty)
    assert(app1.args.isEmpty)
    val json1 = schemaMapper.writeValueAsString(app1)
    val readResult1 = schemaMapper.readValue(json1, classOf[AppDefinition])
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
    val readResult2 = schemaMapper.readValue(json2, classOf[AppDefinition])
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
      maxLaunchDelay = 3.minutes,
      container = Some(
        Container(docker = Some(Container.Docker("group/image")))
      ),
      healthChecks = Set(HealthCheck()),
      dependencies = Set(PathId("/prod/product/backend")),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.75)
    )
    validateJsonSchema(app3, false) // id not valid
    val json3 = schemaMapper.writeValueAsString(app3)
    val readResult3 = schemaMapper.readValue(json3, classOf[AppDefinition])
    assert(readResult3 == app3)

    import java.lang.{ Integer => JInt }

    import mesosphere.marathon.state.Container.Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network

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
    validateJsonSchema(app4)

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
    val readResult4 = schemaMapper.readValue(json4, classOf[AppDefinition])
    assert(readResult4.copy(version = app4.version) == app4)
  }

  test("jackson and play-json parsing has the same result") {
    val fullAppJson = """{
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

    import mesosphere.marathon.api.v2.json.Formats.AppDefinitionReads

    assert(schemaMapper.readValue(fullAppJson, classOf[AppDefinition]) == Json.parse(fullAppJson).as[AppDefinition])
  }

  test("AppDefinition.WithTaskCountsAndDeploymentsWrites output of play-json matches jackson") {
    val app = AppDefinition()

    val task = Protos.MarathonTask
      .newBuilder
      .setHost("localhost")
      .setId("my-task")
      .addPorts(9999)
      .setStagedAt(0)
      .setStartedAt(0)
      .setVersion("some-version")
      .build()

    val appGroup = Group(PathId("/foo"), Set(app))
    val enrichedApp = app.withTaskCountsAndDeployments(Seq(EnrichedTask(app.id, task, Nil, Nil)), HealthCounts(0, 0, 0), Seq(DeploymentPlan(Group.empty, appGroup)))

    import mesosphere.marathon.api.v2.AppsResource.WithTaskCountsAndDeploymentsWrites

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val playRes = Json.parse(Json.toJson(enrichedApp).toString())
    val jacksonRes = Json.parse(mapper.writeValueAsString(enrichedApp))
    assert(playRes == jacksonRes)
  }

  test("AppDefinition.WithTasksAndDeploymentsWrites output of play-json matches jackson") {
    val app = AppDefinition()

    val task = Protos.MarathonTask
      .newBuilder
      .setHost("localhost")
      .setId("my-task")
      .addPorts(9999)
      .setStagedAt(0)
      .setStartedAt(0)
      .setVersion("some-version")
      .build()

    val appGroup = Group(PathId("/foo"), Set(app))

    val enrichedApp = app.withTasksAndDeployments(Seq(EnrichedTask(app.id, task, Nil, Nil)), HealthCounts(0, 0, 0), Seq(DeploymentPlan(Group.empty, appGroup)))

    import mesosphere.marathon.api.v2.AppsResource.WithTasksAndDeploymentsWrites

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val playRes = Json.parse(Json.toJson(enrichedApp).toString())
    val jacksonRes = Json.parse(mapper.writeValueAsString(enrichedApp))
    assert(playRes == jacksonRes)
  }

  test("AppDefinition.WithTasksAndDeploymentsAndFailuresWrites output of play-json matches jackson") {
    val app = AppDefinition()

    val task = Protos.MarathonTask
      .newBuilder
      .setHost("localhost")
      .setId("my-task")
      .addPorts(9999)
      .setStagedAt(0)
      .setStartedAt(0)
      .setVersion("some-version")
      .build()

    val appGroup = Group(PathId("/foo"), Set(app))

    val failure = TaskFailure(
      app.id,
      mesos.TaskID.newBuilder.setValue(task.getId).build(),
      mesos.TaskState.TASK_FAILED
    )

    val enrichedApp = app.withTasksAndDeploymentsAndFailures(Seq(EnrichedTask(app.id, task, Nil, Nil)), HealthCounts(0, 0, 0), Seq(DeploymentPlan(Group.empty, appGroup)), Some(failure))

    import mesosphere.marathon.api.v2.AppsResource.WithTasksAndDeploymentsAndFailuresWrites

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val playRes = Json.parse(Json.toJson(enrichedApp).toString())
    val jacksonRes = Json.parse(mapper.writeValueAsString(enrichedApp))
    assert(playRes == jacksonRes)
  }

  def getScalarResourceValue(proto: ServiceDefinition, name: String) = {
    proto.getResourcesList.asScala
      .find(_.getName == name)
      .get.getScalar.getValue
  }

  test("CustomResources ToProto") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4,
      mem = 256,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd",
      acceptedResourceRoles = Some(Set("a", "b")),
      customResources = Map("foo" -> CustomResource(Some(CustomResource.CustomScalar(10))),
        "bar" -> CustomResource(set = Some(CustomResource.CustomSet(Set("a", "b", "c", "d"), 4))),
        "baz" -> CustomResource(ranges = Some(CustomResource.CustomRanges(
          Seq(CustomResource.CustomRange(5, Some(10L), Some(20L))))))
      )
    )

    validateJsonSchema(app1)

    val proto1: Protos.ServiceDefinition = app1.toProto
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
    assert(1.0 == proto1.getUpgradeStrategy.getMinimumHealthCapacity)
    assert(1.0 == proto1.getUpgradeStrategy.getMaximumOverCapacity)
    assert(proto1.hasAcceptedResourceRoles)
    assert(proto1.getAcceptedResourceRoles == Protos.ResourceRoles.newBuilder().addRole("a").addRole("b").build())
    val customResources = proto1.getCustomResourcesList.asScala.map {
      r => r.getName -> r.getResource
    }.toMap
    assert(customResources("foo").getScalar.getValue == 10)
    assert(customResources("bar").getSet.getValueList.asScala.toList == Seq("a", "b", "c", "d"))
    assert(customResources("bar").getSet.getNumberRequired == 4)
    assert(customResources("baz").getRange.getValueList.asScala(0).getBegin == 10)
  }
}
