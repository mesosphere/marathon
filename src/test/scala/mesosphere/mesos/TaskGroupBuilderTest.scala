package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.core.health.{MesosCommandHealthCheck, MesosHttpHealthCheck, MesosTcpHealthCheck, PortReference}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.silent
import mesosphere.marathon.core.pod._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ApplicationSpec, PodSpec}
import mesosphere.marathon.raml
import mesosphere.marathon.raml.{Endpoint, Resources, Lifecycle}
import mesosphere.marathon.state._
import mesosphere.marathon.state
import scala.jdk.CollectionConverters._
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import mesosphere.marathon.AllConf
import org.apache.mesos.Protos.{ExecutorInfo, TaskGroupInfo, TaskInfo}
import org.apache.mesos.{Protos => mesos}
import org.scalatest.Inside

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class TaskGroupBuilderTest extends UnitTest with Inside {

  implicit val clock = new SettableClock()
  val config = AllConf.withTestConfig()

  val defaultBuilderConfig = TaskGroupBuilder.BuilderConfig(
    acceptedResourceRoles = Set(ResourceRole.Unreserved),
    envVarsPrefix = None,
    mesosBridgeName = raml.Networks.DefaultMesosBridgeName)

  "A TaskGroupBuilder" must {

    "correlate Endpoint with the appropriate network" when {
      "multiple container networks are defined" in {

        val Seq(network1, network2) = TaskGroupBuilder.buildMesosNetworks(
          List(ContainerNetwork("1"), ContainerNetwork("2")),
          List(
            Endpoint("port-1", containerPort = Some(2001), networkNames = List("1")),
            Endpoint("port-2", containerPort = Some(2002), networkNames = List("2"))),
          List(
            Some(1001),
            Some(1002)),
          defaultBuilderConfig.mesosBridgeName)

        network1.getName shouldBe "1"
        inside(network1.getPortMappingsList.asScala.toList) {
          case List(port1) =>
            port1.getContainerPort shouldBe 2001
            port1.getHostPort shouldBe 1001
        }
        inside(network2.getPortMappingsList.asScala.toList) {
          case List(port2) =>
            port2.getContainerPort shouldBe 2002
            port2.getHostPort shouldBe 1002
        }
      }
    }

    "build from a PodDefinition with a single container" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.1, mem = 160.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo",
            exec = None,
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f)
          )
        )
      )
      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)
      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksList.asScala.exists(_.getName == "Foo"))
    }

    "build from a PodDefinition with multiple containers" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.1, mem = 1056.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo",
            resources = raml.Resources(cpus = 1.0f, mem = 512.0f)
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 2.0f, mem = 256.0f)
          ),
          MesosContainer(
            name = "Foo3",
            resources = raml.Resources(cpus = 1.0f, mem = 256.0f)
          )
        )
      )
      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)
      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 3)
    }

    "use the given matched resources" in {
      val offer = MarathonTestHelper.makeBasicOffer(
        cpus = 0.9, mem = 192.0, disk = 60.0, gpus = 8.0, role = "slave0").build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/slave0/pod"), role = "*",
        acceptedResourceRoles = Set("slave0"),
        executorResources = raml.Resources(cpus = 0.1, mem = 32, disk = 10),
        containers = Seq(
          MesosContainer(
            name = "foo",
            resources = raml.Resources(cpus = 0.3, mem = 64.0, disk = 20, gpus = 3)),
          MesosContainer(
            name = "bar",
            resources = raml.Resources(cpus = 0.5, mem = 96.0, disk = 30, gpus = 5))))

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)
      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (executorInfo, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(executorInfo.getResourcesList.asScala.forall(_.getRole == "slave0"))
      assert(executorInfo.getResourcesList.asScala.count { r =>
        r.getRole == "slave0" && r.getName == "cpus" && r.getScalar.getValue == 0.1
      } == 1)
      assert(executorInfo.getResourcesList.asScala.count { r =>
        r.getRole == "slave0" && r.getName == "mem" && r.getScalar.getValue == 32.0
      } == 1)
      assert(executorInfo.getResourcesList.asScala.count { r =>
        r.getRole == "slave0" && r.getName == "disk" && r.getScalar.getValue == 10.0
      } == 1)

      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.forall(_.getRole == "slave0")) == 2)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "cpus" && r.getScalar.getValue == 0.3
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "cpus" && r.getScalar.getValue == 0.5
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "mem" && r.getScalar.getValue == 64.0
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "mem" && r.getScalar.getValue == 96.0
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "disk" && r.getScalar.getValue == 20
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "disk" && r.getScalar.getValue == 30
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "gpus" && r.getScalar.getValue == 3.0
      }) == 1)
      assert(taskGroupInfo.getTasksList.asScala.count(_.getResourcesList.asScala.exists { r =>
        r.getRole == "slave0" && r.getName == "gpus" && r.getScalar.getValue == 5.0
      }) == 1)
    }: @silent

    "set container commands from a MesosContainer definition" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            exec = Some(raml.MesosExec(raml.ShellCommand("foo"))),
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f)
          ),
          MesosContainer(
            name = "Foo2",
            exec = Some(raml.MesosExec(raml.ArgvCommand(Seq("foo", "arg1", "arg2")))),
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f)
          ),
          MesosContainer(
            name = "Foo3",
            exec = Some(raml.MesosExec(raml.ArgvCommand(Seq("foo", "arg1", "arg2")), Some(true))),
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f)
          )
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 3)

      val command1 = taskGroupInfo.getTasksList.asScala.find(_.getName == "Foo1").get.getCommand

      assert(command1.getShell)
      assert(command1.getValue == "foo")
      assert(command1.getArgumentsCount == 0)

      val command2 = taskGroupInfo.getTasksList.asScala.find(_.getName == "Foo2").get.getCommand

      assert(!command2.getShell)
      assert(command2.getValue.isEmpty)
      assert(command2.getArgumentsCount == 3)
      assert(command2.getArguments(0) == "foo")
      assert(command2.getArguments(1) == "arg1")
      assert(command2.getArguments(2) == "arg2")

      val command3 = taskGroupInfo.getTasksList.asScala.find(_.getName == "Foo3").get.getCommand

      assert(!command3.getShell)
      assert(command3.getValue == "foo")
      assert(command3.getArgumentsCount == 3)
      assert(command3.getArguments(0) == "foo")
      assert(command3.getArguments(1) == "arg1")
      assert(command3.getArguments(2) == "arg2")
    }

    "override pod user values with ones defined in containers" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.1, mem = 1056.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f)
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            user = Some("admin")
          )
        ),
        user = Some("user")
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)

      assert(taskGroupInfo.getTasksList.asScala.find(_.getName == "Foo1").get.getCommand.getUser == "user")
      assert(taskGroupInfo.getTasksList.asScala.find(_.getName == "Foo2").get.getCommand.getUser == "admin")
    }

    "set pod labels and container labels" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.1, mem = 1056.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            labels = Map("b" -> "c")
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            labels = Map("c" -> "c")
          )
        ),
        labels = Map("a" -> "a", "b" -> "b")
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (executorInfo, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(executorInfo.hasLabels)

      val executorLabels = executorInfo.getLabels.getLabelsList.asScala.map { label =>
        label.getKey -> label.getValue
      }.toMap

      assert(executorLabels("a") == "a")
      assert(executorLabels("b") == "b")

      assert(taskGroupInfo.getTasksCount == 2)

      val task1labels = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo1").get
        .getLabels.getLabelsList.asScala
        .map(label => label.getKey -> label.getValue).toMap

      assert(task1labels("b") == "c")

      val task2labels = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo2").get
        .getLabels.getLabelsList.asScala
        .map(label => label.getKey -> label.getValue).toMap

      assert(task2labels("c") == "c")
    }

    "simple container environment variables check" in {
      val instanceIdStr = "/product/frontend"
      val containerIdStr = "Foo1"

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.0f, mem = 1024.0f, disk = 10.0).build()
      val mesosContainer = MesosContainer(
        name = containerIdStr,
        resources = raml.Resources(cpus = 2.0f, mem = 512.0f, disk = 0.0f)
      )

      val podSpec = PodDefinition(
        id = AbsolutePathId(instanceIdStr), role = "*",
        containers = Seq(
          mesosContainer
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 1)

      val envVars: Map[String, String] = taskGroupInfo
        .getTasks(0)
        .getCommand
        .getEnvironment
        .getVariablesList
        .asScala
        .iterator
        .map(ev => (ev.getName, ev.getValue))
        .toMap

      assert(envVars("MESOS_EXECUTOR_ID") == instanceId.executorIdString)
      assert(envVars("MESOS_TASK_ID") == Task.Id(instanceId, Some(mesosContainer)).idString)
      assert(envVars("MARATHON_APP_ID") == instanceIdStr)
      assert(envVars.contains("MARATHON_APP_VERSION"))
      assert(envVars("MARATHON_CONTAINER_ID") == containerIdStr)
    }

    "set environment variables and make sure that container variables override pod variables" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.1, mem = 1056.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            env = Map("b" -> EnvVarString("c"))
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            env = Map("c" -> EnvVarString("c")),
            labels = Map("b" -> "b")
          )
        ),
        env = Map("a" -> EnvVarString("a"), "b" -> EnvVarString("b")),
        labels = Map("a" -> "a")
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)

      val task1EnvVars = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo1").get
        .getCommand
        .getEnvironment
        .getVariablesList
        .asScala
        .map(envVar => envVar.getName -> envVar.getValue).toMap

      assert(task1EnvVars("a") == "a")
      assert(task1EnvVars("b") == "c")
      assert(task1EnvVars("MARATHON_APP_ID") == "/product/frontend")
      assert(task1EnvVars("MARATHON_CONTAINER_ID") == "Foo1")
      assert(task1EnvVars("MARATHON_APP_LABELS") == "A")
      assert(task1EnvVars("MARATHON_APP_LABEL_A") == "a")

      val task2EnvVars = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo2").get
        .getCommand
        .getEnvironment
        .getVariablesList
        .asScala
        .map(envVar => envVar.getName -> envVar.getValue).toMap

      assert(task2EnvVars("a") == "a")
      assert(task2EnvVars("b") == "b")
      assert(task2EnvVars("c") == "c")
      assert(task2EnvVars("MARATHON_APP_ID") == "/product/frontend")
      assert(task2EnvVars("MARATHON_CONTAINER_ID") == "Foo2")
      assert(task2EnvVars("MARATHON_APP_LABELS") == "A B")
      assert(task2EnvVars("MARATHON_APP_LABEL_A") == "a")
      assert(task2EnvVars("MARATHON_APP_LABEL_B") == "b")
    }

    "create volume mappings between volumes defined for a pod and container mounts" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.1, mem = 1056.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            volumeMounts = Seq(
              state.VolumeMount(
                volumeName = Some("volume1"),
                mountPath = "/mnt/path1"
              ),
              state.VolumeMount(
                volumeName = Some("volume2"),
                mountPath = "/mnt/path2",
                readOnly = true
              ),
              state.VolumeMount(
                volumeName = Some("volume3"),
                mountPath = "/mnt/path3"
              )
            )
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            volumeMounts = Seq(
              state.VolumeMount(
                volumeName = Some("volume1"),
                mountPath = "/mnt/path2",
                readOnly = false
              ),
              state.VolumeMount(
                volumeName = Some("volume3"),
                mountPath = "/mnt/path3"
              )
            )
          )
        ),
        volumes = Seq(
          state.HostVolume(
            name = Some("volume1"),
            hostPath = "/mnt/path1"
          ),
          state.EphemeralVolume(
            name = Some("volume2")
          ),
          state.PersistentVolume(
            name = Some("volume3"),
            persistent = PersistentVolumeInfo(
              size = 512
            )
          )
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)

      val task1Volumes = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo1").get
        .getContainer.getVolumesList.asScala

      assert(task1Volumes.size == 3)
      assert(task1Volumes.find(_.getContainerPath == "/mnt/path1").get.getHostPath == "/mnt/path1")
      assert(task1Volumes.find(_.getContainerPath == "/mnt/path1").get.getMode == mesos.Volume.Mode.RW)
      assert(task1Volumes.find(_.getContainerPath == "/mnt/path2").get.getMode == mesos.Volume.Mode.RO)
      assert(task1Volumes.find(_.getContainerPath == "/mnt/path3").get.getMode == mesos.Volume.Mode.RW)

      val task2Volumes = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo2").get
        .getContainer.getVolumesList.asScala

      assert(task2Volumes.size == 2)
      assert(task2Volumes.find(_.getContainerPath == "/mnt/path2").get.getHostPath == "/mnt/path1")
      assert(task2Volumes.find(_.getContainerPath == "/mnt/path2").get.getMode == mesos.Volume.Mode.RW)
      assert(task1Volumes.find(_.getContainerPath == "/mnt/path3").get.getMode == mesos.Volume.Mode.RW)
    }

    "create a pod of one container with Docker pull config" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 6.1, mem = 1568.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 2.0f, mem = 512.0f),
            image = Some(
              raml.Image(
                kind = raml.ImageType.Docker,
                id = "alpine",
                pullConfig = Some(raml.DockerPullConfig("aSecret")),
                forcePull = Some(true)
              ))
          )))

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 1)

      val taskContainer = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo1").get.getContainer

      assert(taskContainer.getType == mesos.ContainerInfo.Type.MESOS)
      assert(taskContainer.getMesos.getImage.getType == mesos.Image.Type.DOCKER)
      assert(taskContainer.getMesos.getImage.getDocker.getName == "alpine")
      assert(taskContainer.getMesos.getImage.getDocker.getConfig.getType == mesos.Secret.Type.REFERENCE)
      assert(taskContainer.getMesos.getImage.getDocker.getConfig.getReference.getName == "aSecret")
      assert(!taskContainer.getMesos.getImage.getCached)
    }

    "create health check definitions with host-mode networking" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 1200, endPort = 1300).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        networks = Seq(HostNetwork),
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("foo1")), path = Some("healthcheck"))),
            endpoints = Seq(
              raml.Endpoint(
                name = "foo1",
                hostPort = Some(1234)
              )
            )
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            healthCheck = Some(MesosTcpHealthCheck(portIndex = Some(PortReference("foo2")))),
            endpoints = Seq(
              raml.Endpoint(
                name = "foo2",
                hostPort = Some(1235)
              )
            )
          ),
          MesosContainer(
            name = "Foo3",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            healthCheck = Some(MesosCommandHealthCheck(command = Command("foo")))
          )
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 3)

      val task1HealthCheck = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo1").get
        .getHealthCheck

      assert(task1HealthCheck.getType == mesos.HealthCheck.Type.HTTP)
      assert(task1HealthCheck.getHttp.getPort == 1234)
      assert(task1HealthCheck.getHttp.getPath == "healthcheck")

      val task2HealthCheck = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo2").get
        .getHealthCheck

      assert(task2HealthCheck.getType == mesos.HealthCheck.Type.TCP)
      assert(task2HealthCheck.getTcp.getPort == 1235)

      val task3HealthCheck = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo3").get
        .getHealthCheck

      assert(task3HealthCheck.getType == mesos.HealthCheck.Type.COMMAND)
      assert(task3HealthCheck.getCommand.getShell)
      assert(task3HealthCheck.getCommand.getValue == "foo")
    }

    "create health check definitions with container-mode networking" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 1200, endPort = 1300).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        networks = Seq(ContainerNetwork("dcosnetwork")),
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("foo1")), path = Some("healthcheck"))),
            endpoints = Seq(
              raml.Endpoint(
                name = "foo1",
                containerPort = Some(1234)
              )
            )
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            healthCheck = Some(MesosTcpHealthCheck(portIndex = Some(PortReference("foo2")))),
            endpoints = Seq(
              raml.Endpoint(
                name = "foo2",
                containerPort = Some(1235)
              )
            )
          )
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)

      val task1HealthCheck = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo1").get
        .getHealthCheck

      assert(task1HealthCheck.getType == mesos.HealthCheck.Type.HTTP)
      assert(task1HealthCheck.getHttp.getPort == 1234)
      assert(task1HealthCheck.getHttp.getPath == "healthcheck")

      val task2HealthCheck = taskGroupInfo
        .getTasksList.asScala.find(_.getName == "Foo2").get
        .getHealthCheck

      assert(task2HealthCheck.getType == mesos.HealthCheck.Type.TCP)
      assert(task2HealthCheck.getTcp.getPort == 1235)
    }

    "create pod with container-mode bridge networking" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 1200, endPort = 1300).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        networks = Seq(BridgeNetwork()),
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            endpoints = Seq(
              raml.Endpoint(
                name = "foo1",
                hostPort = Some(1201),
                containerPort = Some(1211)
              )
            )
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            endpoints = Seq(
              raml.Endpoint(
                name = "foo2",
                hostPort = Some(0),
                containerPort = Some(1212)
              )
            )
          )
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (executorInfo, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)

      val networkInfo = executorInfo.getContainer.getNetworkInfosList.get(0)
      assert(networkInfo.getName() == defaultBuilderConfig.mesosBridgeName)

      val portMappings = networkInfo.getPortMappingsList

      assert(portMappings.asScala.count(_.getContainerPort == 1211) == 1)
      assert(portMappings.asScala.count(_.getContainerPort == 1212) == 1)
      assert(portMappings.asScala.find(_.getContainerPort == 1211).get.getHostPort == 1201)
      assert(portMappings.asScala.find(_.getContainerPort == 1212).get.getHostPort != 0)
    }

    "support URL artifacts" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.1, mem = 160.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            artifacts = Seq(
              raml.Artifact(
                uri = "foo"
              )
            )
          )
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      val task1Artifacts = taskGroupInfo.getTasksList.asScala.find(_.getName == "Foo1").get.getCommand.getUrisList
      assert(task1Artifacts.size == 1)

      assert(task1Artifacts.asScala.head.getValue == "foo")
    }

    "executor info has correct values" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 21.1, mem = 256.0, disk = 10.0).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            artifacts = Seq(
              raml.Artifact(
                uri = "foo"
              )
            )
          )
        ),
        executorResources = Resources(cpus = 20.0)
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (executorInfo, _, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      val cpuExecutorInfo = executorInfo.getResourcesList.asScala.find(info => info.getName == "cpus")
      assert(cpuExecutorInfo.isDefined)
      assert(cpuExecutorInfo.get.getScalar.getValue == 20.0)
      assert(podSpec.resources.cpus == 21.0)
    }

    "support networks and port mappings for pods and containers" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 8000, endPort = 9000).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            endpoints = Seq(
              raml.Endpoint(
                name = "webserver",
                containerPort = Some(80),
                hostPort = Some(8080),
                protocol = Seq("tcp", "udp"),
                labels = Map("VIP_0" -> "1.1.1.1:8888")
              )
            )
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            endpoints = Seq(
              raml.Endpoint(
                name = "webapp",
                containerPort = Some(1234),
                hostPort = Some(0),
                protocol = Seq("tcp")
              ),
              raml.Endpoint(
                name = "webapp-tls",
                containerPort = Some(1235),
                protocol = Seq("tcp"),
                labels = Map("VIP_1" -> "2.2.2.2:9999")
              )
            )
          )
        ),
        networks = Seq(
          ContainerNetwork("network-a")
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (executorInfo, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)
      val task1 = taskGroupInfo.getTasks(0)
      assert(task1.hasDiscovery)
      val task1Ports = task1.getDiscovery.getPorts.getPortsList.asScala.to(Seq)
      assert(task1Ports.map(_.getProtocol) == Seq("tcp", "udp"))
      assert(task1Ports.count(_.getName == "webserver") == 2)

      withClue("expected network-scope=host and VIP_0 in port discovery info, for both tcp and udp protocols") {
        task1Ports.forall { p =>
          p.getNumber == 8080 && {
            val labels: Map[String, String] = p.getLabels.getLabelsList.asScala.to(Seq).map(l => l.getKey -> l.getValue).toMap
            labels.get("network-scope").contains("host") && labels.get("VIP_0").contains("1.1.1.1:8888")
          }
        } shouldBe true
      }

      val task2 = taskGroupInfo.getTasks(1)
      val task2Ports = task2.getDiscovery.getPorts.getPortsList.asScala.to(Seq)
      assert(task2Ports.map(_.getProtocol) == Seq("tcp", "tcp"))

      task2Ports.exists{ p =>
        p.getName == "webapp" && p.getNumber != 1234 && {
          val labels: Map[String, String] = p.getLabels.getLabelsList.asScala.to(Seq).map(l => l.getKey -> l.getValue).toMap
          labels.get("network-scope").contains("host") && !labels.exists { case (k, v) => k.startsWith("VIP_") }
        }
      } shouldBe true

      task2Ports.exists{ p =>
        p.getName == "webapp-tls" && p.getNumber == 1235 && {
          val labels: Map[String, String] = p.getLabels.getLabelsList.asScala.to(Seq).map(l => l.getKey -> l.getValue).toMap
          labels.get("network-scope").contains("container") && labels.get("VIP_1").contains("2.2.2.2:9999")
        }
      } shouldBe true

      assert(executorInfo.getContainer.getNetworkInfosCount == 1)

      val networkInfo = executorInfo.getContainer.getNetworkInfosList.asScala.find(_.getName == "network-a")

      assert(networkInfo.isDefined)

      val portMappings = networkInfo.get.getPortMappingsList.asScala

      assert(portMappings.filter(_.getContainerPort == 80).find(_.getProtocol == "tcp").get.getHostPort == 8080)
      assert(portMappings.filter(_.getContainerPort == 80).find(_.getProtocol == "udp").get.getHostPort == 8080)
      assert(portMappings.find(_.getContainerPort == 1234).get.getHostPort != 0)
    }

    "endpoint env is set on each container" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 8000, endPort = 9000).build

      val podSpec = PodDefinition(
        id = AbsolutePathId("/product/frontend"), role = "*",
        containers = Seq(
          MesosContainer(
            name = "Foo1",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            endpoints = Seq(
              raml.Endpoint(
                name = "webserver",
                containerPort = Some(80),
                hostPort = Some(8080),
                protocol = Seq("tcp", "udp")
              )
            )
          ),
          MesosContainer(
            name = "Foo2",
            resources = raml.Resources(cpus = 1.0f, mem = 128.0f),
            endpoints = Seq(
              raml.Endpoint(
                name = "webapp",
                containerPort = Some(1234),
                hostPort = Some(8081)
              )
            )
          )
        ),
        networks = Seq(
          ContainerNetwork("network-a")
        )
      )

      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      assert(taskGroupInfo.getTasksCount == 2)
      val task1Env = taskGroupInfo.getTasks(0).getCommand.getEnvironment.getVariablesList.asScala.map(v => v.getName -> v.getValue).toMap
      val task2Env = taskGroupInfo.getTasks(1).getCommand.getEnvironment.getVariablesList.asScala.map(v => v.getName -> v.getValue).toMap
      assert(task1Env("ENDPOINT_WEBSERVER") == "80")
      assert(task1Env("ENDPOINT_WEBAPP") == "1234")
      assert(task1Env("EP_HOST_WEBSERVER") == "8080")
      assert(task1Env("EP_CONTAINER_WEBSERVER") == "80")
      assert(task1Env("EP_HOST_WEBAPP") == "8081")
      assert(task1Env("EP_CONTAINER_WEBAPP") == "1234")
      assert(task2Env("ENDPOINT_WEBSERVER") == "80")
      assert(task2Env("ENDPOINT_WEBAPP") == "1234")
      assert(task2Env("EP_HOST_WEBSERVER") == "8080")
      assert(task2Env("EP_CONTAINER_WEBSERVER") == "80")
      assert(task2Env("EP_HOST_WEBAPP") == "8081")
      assert(task2Env("EP_CONTAINER_WEBAPP") == "1234")
    }

    "A RunSpecTaskProcessor is able to customize the created TaskGroups" in {
      val runSpecTaskProcessor = new RunSpecTaskProcessor {
        override def taskInfo(runSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = ???
        override def taskGroup(runSpec: PodSpec, exec: ExecutorInfo.Builder, builder: TaskGroupInfo.Builder): Unit = {
          val taskList = builder.getTasksList
          builder.clearTasks()
          taskList.asScala.foreach { task => builder.addTasks(task.toBuilder.setName(task.getName + "-extended")) }
        }
      }

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 4.1, mem = 1056.0, disk = 10.0).build
      val container = MesosContainer(name = "foo", resources = raml.Resources(cpus = 1.0f, mem = 128.0f))
      val podSpec = PodDefinition(id = AbsolutePathId("/product/frontend"), role = "*", containers = Seq(container))
      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Seq.empty,
        defaultBuilderConfig.acceptedResourceRoles, config, Seq.empty)

      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer, instanceId, taskIds, defaultBuilderConfig, runSpecTaskProcessor,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch, None, true
      )
      taskGroupInfo.getTasksCount should be(1)
      taskGroupInfo.getTasks(0).getName should be(s"${container.name}-extended")
    }

    "tty defined in a pod container will render ContainerInfo correctly" in {
      val container = MesosContainer(name = "withTTY", resources = Resources(), tty = Some(true))
      val pod = PodDefinition(id = AbsolutePathId("/tty"), role = "*", containers = Seq(container))
      val containerInfo = TaskGroupBuilder.computeContainerInfo(pod, container)
      containerInfo should be(defined)
      containerInfo.get.hasTtyInfo should be(true)
      containerInfo.get.getTtyInfo.hasWindowSize should be(false)
    }

    "no tty defined in a pod container will render ContainerInfo without tty" in {
      val container = MesosContainer(name = "withTTY", resources = Resources())
      val pod = PodDefinition(id = AbsolutePathId("/notty"), role = "*", containers = Seq(container))
      val containerInfo = TaskGroupBuilder.computeContainerInfo(pod, container)
      containerInfo should be(empty)
    }

    "IpcConfig defined on a pod renderes to executor info" in {
      val ipcShmSize = 64
      val ipcMode = IpcMode.Private

      val ipcModeContainer = IpcMode.ShareParent
      val ipcShmSizeContainer = 32

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 8000, endPort = 9000).build
      val container = MesosContainer(name = "dummy", resources = Resources(), linuxInfo = Some(LinuxInfo(seccomp = None, ipcInfo = Some(IPCInfo(ipcMode = ipcModeContainer, shmSize = Some(ipcShmSizeContainer))))))

      val podSpec = PodDefinition(id = AbsolutePathId("/ipcConfig"), containers = Seq(container), linuxInfo = Some(LinuxInfo(seccomp = None, ipcInfo = Some(IPCInfo(ipcMode = ipcMode, shmSize = Some(ipcShmSize))))), role = "*")
      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Nil,
        defaultBuilderConfig.acceptedResourceRoles, config, Nil)

      val (executorInfo, taskGroup, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      executorInfo.hasContainer should be(true)
      executorInfo.getContainer.hasLinuxInfo should be(true)
      executorInfo.getContainer.getLinuxInfo.hasIpcMode should be(true)
      executorInfo.getContainer.getLinuxInfo.getIpcMode should be(mesos.LinuxInfo.IpcMode.PRIVATE)
      executorInfo.getContainer.getLinuxInfo.hasShmSize should be(true)
      executorInfo.getContainer.getLinuxInfo.getShmSize should be(ipcShmSize)

      taskGroup.getTasksCount should be(1)
      val task = taskGroup.getTasksList.get(0)
      task.hasContainer should be(true)
      task.getContainer.hasLinuxInfo should be(true)
      task.getContainer.getLinuxInfo.hasIpcMode should be(true)
      task.getContainer.getLinuxInfo.getIpcMode should be(mesos.LinuxInfo.IpcMode.SHARE_PARENT)
      task.getContainer.getLinuxInfo.hasShmSize should be(true)
      task.getContainer.getLinuxInfo.getShmSize should be(ipcShmSizeContainer)

    }

    "killPolicy is specified correctly" in {
      val killDuration = 3.seconds
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 3.1, mem = 416.0, disk = 10.0, beginPort = 8000, endPort = 9000).build
      val container = MesosContainer(
        name = "withTTY",
        resources = Resources(),
        tty = Some(true),
        lifecycle = Some(Lifecycle(Some(killDuration.toSeconds.toDouble))))

      val podSpec = PodDefinition(id = AbsolutePathId("/tty"), role = "*", containers = Seq(container))
      val instanceId = Instance.Id.forRunSpec(podSpec.id)
      val taskIds = podSpec.containers.map(c => Task.Id(instanceId, Some(c)))
      val resourceMatch = RunSpecOfferMatcher.matchOffer(podSpec, offer, Nil,
        defaultBuilderConfig.acceptedResourceRoles, config, Nil)
      val (_, taskGroupInfo, _) = TaskGroupBuilder.build(
        podSpec,
        offer,
        instanceId,
        taskIds,
        defaultBuilderConfig,
        RunSpecTaskProcessor.empty,
        resourceMatch.asInstanceOf[ResourceMatchResponse.Match].resourceMatch,
        None,
        true
      )

      taskGroupInfo.getTasks(0).getKillPolicy.getGracePeriod.getNanoseconds shouldBe (killDuration.toNanos)
    }
  }
}
