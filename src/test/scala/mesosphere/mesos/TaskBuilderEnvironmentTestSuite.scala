package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, _ }
import mesosphere.mesos.protos.TaskID
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.joda.time.{ DateTime, DateTimeZone }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderEnvironmentTestSuite extends TaskBuilderSuiteBase {

  "TaskBuilder" when {

    "given host ports 1001 and 1002" should {

      val env = TaskBuilder.portsEnv(Seq(0, 0), Helpers.hostPorts(1001, 1002), Seq(None, None))

      "set env variable PORT" in { env("PORT") should be("1001") }
      "set env variable PORT0" in { env("PORT0") should be("1001") }
      "set env variable PORT1" in { env("PORT1") should be("1002") }
      "not set env variable PORT_1" in { env.keys should not contain ("PORT_0") }
    }

    "given no ports" should {

      val env = TaskBuilder.portsEnv(Seq(), Seq(), Seq())

      "not set any env variable" in { env should be('empty) }
    }

    "given protocols" should {
      val env = TaskBuilder.portsEnv(Seq(0, 0), Helpers.hostPorts(1001, 1002), Seq(Some("http"), Some("https")))

      "set env variable PORT" in { env("PORT") should be("1001") }
      "set env variable PORT0" in { env("PORT0") should be("1001") }
      "set env variable PORT1" in { env("PORT1") should be("1002") }

      "set env variable PORT_HTTP" in { env("PORT_HTTP") should be("1001") }
      "set env variable PORT_HTTPS" in { env("PORT_HTTPS") should be("1002") }
    }

    "given declared ports" should {
      val env = TaskBuilder.portsEnv(Seq(80, 8080), Helpers.hostPorts(1001, 1002), Seq(None, None))

      "set env variable PORT" in { env("PORT") should be("1001") }
      "set env variable PORT0" in { env("PORT0") should be("1001") }
      "set env variable PORT1" in { env("PORT1") should be("1002") }

      "set env variable PORT_80" in { env("PORT_80") should be("1001") }
      "set env variable PORT_8080" in { env("PORT_8080") should be("1002") }
    }

    "given deckared named ports" should {
      val env = TaskBuilder.portsEnv(Seq(80, 8080, 443), Helpers.hostPorts(1001, 1002, 1003), Seq(Some("http"), None, Some("https")))

      "set env variable PORT" in { env("PORT") should be("1001") }
      "set env variable PORT0" in { env("PORT0") should be("1001") }
      "set env variable PORT1" in { env("PORT1") should be("1002") }
      "set env variable PORT2" in { env("PORT2") should be("1003") }

      "set env variable PORT_80" in { env("PORT_80") should be("1001") }
      "set env variable PORT_8080" in { env("PORT_8080") should be("1002") }
      "set env variable PORT_443" in { env("PORT_443") should be("1003") }

      "set env variable PORT_HTTP" in { env("PORT_HTTP") should be("1001") }
      "set env variable PORT_HTTPS" in { env("PORT_HTTPS") should be("1003") }
    }

    "an app definition without an task id" should {
      val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
      val runSpec = AppDefinition(
        id = PathId("/app"),
        versionInfo = version
      )
      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, taskId = None)

      "not set any env variable" in { env should be('empty) }
    }

    "given a minimal app definition" should {
      val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
      val runSpec = AppDefinition(
        id = PathId("/app"),
        versionInfo = version
      )
      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, taskId = Some(Task.Id("taskId")))

      "set env variable MESOS_TASK_ID" in { env("MESOS_TASK_ID") should be("taskId") }
      "set env variable MARATHON_APP_ID" in { env("MESOS_APP_ID") should be("/app") }
      "set env variable MARATHON_APP_VERSION" in { env("MESOS_APP_VERSION") should be("2015-02-03T12:30:00.000Z") }
      "set env variable MARATHON_APP_RESOURCE_CPUS" in { env("MARATHON_APP_RESOURCE_CPUS") should be(AppDefinition.DefaultCpus.toString) }
      "set env variable MARATHON_APP_RESOURCE_MEM" in { env("MARATHON_APP_RESOURCE_MEM") should be (AppDefinition.DefaultMem.toString) }
      "set env variable MARATHON_APP_RESOURCE_DISK" in { env("MARATHON_APP_RESOURCE_DISK") should be(AppDefinition.DefaultDisk.toString) }
      "set env variable MARATHON_APP_RESOURCE_GPUS" in { env("MARATHON_APP_RESOURCE_GPUS") should be(AppDefinition.DefaultGpus.toString) }
      "set env variable MARATHON_APP_LABELS" in { env("MARATHON_APP_LABELS") should be("") }
    }

    "given an app defintion with all fields defined" should {
      val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
      val taskId = TaskID("taskId")
      val runSpec = AppDefinition(
        id = PathId("/app"),
        versionInfo = version,
        container = Some(Docker(
          image = "myregistry/myimage:version"
        )),
        cpus = 10.0,
        mem = 256.0,
        disk = 128.0,
        gpus = 2,
        labels = Map(
          "LABEL1" -> "VALUE1",
          "LABEL2" -> "VALUE2"
        )
      )
      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, Some(Task.Id(taskId)))

      "set env variable MESOS_TASK_ID" in { env("MESOS_TASK_ID") should be("taskId") }
      "set env variable MARATHON_APP_ID" in { env("MARATHON_APP_ID") should be("/app") }
      "set env variable MARATHON_APP_VERSION" in { env("MARATHON_APP_VERSION") should be("2015-02-03T12:30:00.000Z") }
      "set env variable MARATHON_APP_DOCKER_IMAGE" in { env("MARATHON_APP_DOCKER_IMAGE") should be("myregistry/myimage:version") }
      "set env variable MARATHON_APP_RESOURCE_CPUS" in { env("MARATHON_APP_RESOURCE_CPUS") should be("10.0") }
      "set env variable MARATHON_APP_RESOURCE_MEM" in { env("MARATHON_APP_RESOURCE_MEM") should be("256.0") }
      "set env variable MARATHON_APP_RESOURCE_DISK" in { env("MARATHON_APP_RESOURCE_DISK") should be("128.0") }
      "set env variable MARATHON_APP_RESOURCE_GPUS" in { env("MARATHON_APP_RESOURCE_GPUS") should be("2") }
      "set env variable MARATHON_APP_LABELS" in { env("MARATHON_APP_LABELS") should be("LABEL1 LABEL2") }
      "set env variable MARATHON_APP_LABEL_LABEL1" in { env("MARATHON_APP_LABEL_LABEL1") should be("VALUE1") }
      "set env variable MARATHON_APP_LABEL_LABEL2" in { env("MARATHON_APP_LABEL_LABEL2") should be("VALUE2") }
    }

    "given unsecure labels" should {

      // will exceed max length for sure
      val longLabel = "longlabel" * TaskBuilder.maxVariableLength
      var longValue = "longvalue" * TaskBuilder.maxEnvironmentVarLength

      val runSpec = AppDefinition(
        labels = Map(
          "label" -> "VALUE1",
          "label-with-invalid-chars" -> "VALUE2",
          "other--label\\--\\a" -> "VALUE3",
          longLabel -> "value for long label",
          "label-long" -> longValue
        )
      )

      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, Some(Task.Id("taskId")))
        .filterKeys(_.startsWith("MARATHON_APP_LABEL"))

      "set env variable MARATHON_APP_LABELS" in { env("MARATHON_APP_LABELS") should be("OTHER_LABEL_A LABEL LABEL_WITH_INVALID_CHARS") }
      "set env variable MARATHON_APP_LABEL_LABEL" in { env("MARATHON_APP_LABEL_LABEL") should be("VALUE1") }
      "set env variable MARATHON_APP_LABEL_LABEL_WITH_INVALID_CHARS" in { env("MARATHON_APP_LABEL_LABEL_WITH_INVALID_CHARS") should be("VALUE2") }
      "set env variable MARATHON_APP_LABEL_OTHER_LABEL_A" in { env("MARATHON_APP_LABEL_OTHER_LABEL_A") should be("VALUE3") }
    }

    "given an app definition with an DOCKER containter image" should {
      val command =
        TaskBuilder.commandInfo(
          runSpec = AppDefinition(
            id = "/test".toPath,
            portDefinitions = PortDefinitions(8080, 8081),
            container = Some(Docker(
              image = "myregistry/myimage:version"
            ))
          ),
          taskId = Some(Task.Id("task-123")),
          host = Some("host.mega.corp"),
          hostPorts = Helpers.hostPorts(1000, 1001),
          envPrefix = None
        )
      val env: Map[String, String] =
        command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

      "set env variable MESOS_TASK_ID" in { env("MESOS_TASK_ID") should be("task-123") }
      "set env variable MARATHON_APP_ID" in { env("MARATHON_APP_ID") should be("/test") }
      "set env variable MARATHON_APP_VERSION" in { env("MARATHON_APP_VERSION") should be("1970-01-01T00:00:00.000Z") }
      "set env variable MARATHON_APP_DOCKER_IMAGE" in { env("MARATHON_APP_DOCKER_IMAGE") should be("myregistry/myimage:version") }
    }
  }

  "given an app definition with port overrides" should {
    // why?
    // see https://github.com/mesosphere/marathon/issues/905

    val command =
      TaskBuilder.commandInfo(
        runSpec = AppDefinition(
          id = "/test".toPath,
          portDefinitions = PortDefinitions(8080, 8081),
          env = EnvVarValue(Map(
            "PORT" -> "1",
            "PORTS" -> "ports",
            "PORT0" -> "1",
            "PORT1" -> "2",
            "PORT_8080" -> "port8080",
            "PORT_8081" -> "port8081"
          ))
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        hostPorts = Helpers.hostPorts(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    "set env variable PORT" in { env("PORT") should be("1") }
    "set env variable PORTS" in { env("PORTS") should be("ports") }
    "set env variable PORT0" in { env("PORT0") should be("1") }
    "set env variable PORT1" in { env("PORT1") should be("2") }
    "set env variable PORT_8080" in { env("PORT_8080") should be("port8080") }
    "set env variable PORT_8081" in { env("PORT_8081") should be("port8081") }
  }

  "given a task command with ports only" should {
    val command =
      TaskBuilder.commandInfo(
        runSpec = AppDefinition(
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        hostPorts = Helpers.hostPorts(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    "set env variable PORT_8080" in { env("PORT_8080") should be("1000") }
    "set env variable PORT_8081" in { env("PORT_8081") should be("1001") }
  }

  "given a task command with custom prefix" should {
    val command =
      TaskBuilder.commandInfo(
        AppDefinition(
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        Some(Task.Id("task-123")),
        Some("host.mega.corp"),
        Helpers.hostPorts(1000, 1001),
        Some("CUSTOM_PREFIX_")
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    "set env variable CUSTOM_PREFIX_PORTS" in { env("CUSTOM_PREFIX_PORTS") should be("1000,1001") }

    "set env variable CUSTOM_PREFIX_PORT" in { env("CUSTOM_PREFIX_PORT") should be("1000") }

    "set env variable CUSTOM_PREFIX_PORT0" in { env("CUSTOM_PREFIX_PORT0") should be("1000") }
    "set env variable CUSTOM_PREFIX_PORT_8080" in { env("CUSTOM_PREFIX_PORT_8080") should be("1000") }

    "set env variable CUSTOM_PREFIX_PORT1" in { env("CUSTOM_PREFIX_PORT1") should be("1001") }
    "set env variable CUSTOM_PREFIX_PORT_8081" in { env("CUSTOM_PREFIX_PORT_8081") should be("1001") }

    "set env variable CUSTOM_PREFIX_HOST" in { env("CUSTOM_PREFIX_HOST") should be("host.mega.corp") }

    "not set env variable HOST" in { env should not contain ("HOST") }
    "not set env variable PORTS" in { env should not contain ("PORTS") }
    "not set env variable PORT0" in { env should not contain ("PORT0") }
    "not set env variable PORT1" in { env should not contain ("PORT1") }

    "set env variable MESOS_TASK_ID" in { env("MESOS_TASK_ID") should be("task-123") }
    "set env variable MARATHON_APP_ID" in { env("MARATHON_APP_ID") should be("/test") }
    "set env variable MARATHON_APP_VERSION" in { env("MARATHON_APP_VERSION") should be("1970-01-01T00:00:00.000Z") }
  }

  "given a task command with custom prefix" should {
    val command =
      TaskBuilder.commandInfo(
        AppDefinition(
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        Some(Task.Id("task-123")),
        Some("host.mega.corp"),
        Helpers.hostPorts(1000, 1001),
        Some("P_")
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    val nonPrefixedEnvVars = env.filterKeys(!_.startsWith("P_"))

    val whiteList = Seq("MESOS_TASK_ID", "MARATHON_APP_ID", "MARATHON_APP_VERSION", "MARATHON_APP_RESOURCE_CPUS",
      "MARATHON_APP_RESOURCE_MEM", "MARATHON_APP_RESOURCE_DISK", "MARATHON_APP_RESOURCE_GPUS", "MARATHON_APP_LABELS")

    "only whitelist unprefixed variables" in { assert(nonPrefixedEnvVars.keySet.forall(whiteList.contains)) }
  }

  "given a task command with port mappings" should {
    val command =
      TaskBuilder.commandInfo(
        runSpec = AppDefinition(
          container = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
              PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 9000, protocol = "tcp", name = Some("http")),
              PortMapping(containerPort = 8081, hostPort = Some(0), servicePort = 9000, protocol = "tcp", name = Some("jabber"))
            ))
          ))
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        hostPorts = Helpers.hostPorts(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    "set env variable PORT_8080" in { env("PORT_8080") should be("1000") }
    "set env variable PORT_8081" in { env("PORT_8081") should be("1001") }
    "set env variable PORT_HTTP" in { env("PORT_HTTP") should be("1000") }
    "set env variable PORT_JABBER" in { env("PORT_JABBER") should be("1001") }
  }

  "given a task command with ports and ports mappings" should {
    val command =
      TaskBuilder.commandInfo(
        runSpec = AppDefinition(
          portDefinitions = PortDefinitions(22, 23),
          container = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
              PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 9000, protocol = "tcp"),
              PortMapping(containerPort = 8081, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
            ))
          ))
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        hostPorts = Helpers.hostPorts(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    "set env variable PORT_8080" in { env("PORT_8080") should be("1000") }
    "set env variable PORT_8081" in { env("PORT_8081") should be("1001") }

    "no set env variable PORT_22" in { env should not contain ("PORT_22") }
    "no set env variable PORT_23" in { env should not contain ("PORT_23") }
  }
}
