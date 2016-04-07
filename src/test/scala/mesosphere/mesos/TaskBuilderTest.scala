package mesosphere.mesos

import com.google.common.collect.Lists
import com.google.protobuf.TextFormat
import mesosphere.marathon.state.AppDefinition.VersionInfo.OnlyVersion
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Container, PathId, Timestamp, _ }
import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec, Protos }
import mesosphere.mesos.protos.{ Resource, TaskID, _ }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => MesosProtos }
import org.joda.time.{ DateTime, DateTimeZone }
import org.scalatest.Matchers

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderTest extends MarathonSpec with Matchers {

  import mesosphere.mesos.protos.Implicits._

  test("BuildIfMatches") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    assertTaskInfo(taskInfo, taskPorts, offer)

    assert(!taskInfo.hasLabels)
  }

  test("BuildIfMatches with port name and labels") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = Seq(
          PortDefinition(8080, "tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
          PortDefinition(8081, "tcp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
        )
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get

    val discoveryInfo = taskInfo.getDiscovery
    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(
        MesosProtos.Ports.newBuilder
          .addPorts(
            MesosProtos.Port.newBuilder.setName("http").setNumber(taskPorts(0)).setProtocol("tcp")
              .setLabels(MesosProtos.Labels.newBuilder().addLabels(
                MesosProtos.Label.newBuilder().setKey("VIP").setValue("127.0.0.1:8080")
              ))
          ).addPorts(
              MesosProtos.Port.newBuilder.setName("admin").setNumber(taskPorts(1)).setProtocol("tcp")
                .setLabels(MesosProtos.Labels.newBuilder().addLabels(
                  MesosProtos.Label.newBuilder().setKey("VIP").setValue("127.0.0.1:8081")
                ))
            )
      ).build

    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)
  }

  test("BuildIfMatches with port name, protocol and labels") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = Seq(
          PortDefinition(8080, "tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
          PortDefinition(8081, "udp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
        )
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get

    val discoveryInfo = taskInfo.getDiscovery
    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(
        MesosProtos.Ports.newBuilder
          .addPorts(
            MesosProtos.Port.newBuilder.setName("http").setNumber(taskPorts(0)).setProtocol("tcp")
              .setLabels(MesosProtos.Labels.newBuilder().addLabels(
                MesosProtos.Label.newBuilder().setKey("VIP").setValue("127.0.0.1:8080")
              ))
          ).addPorts(
              MesosProtos.Port.newBuilder.setName("admin").setNumber(taskPorts(1)).setProtocol("udp")
                .setLabels(MesosProtos.Labels.newBuilder().addLabels(
                  MesosProtos.Label.newBuilder().setKey("VIP").setValue("127.0.0.1:8081")
                ))
            )
      ).build

    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)
  }

  test("BuildIfMatches with port mapping with name, protocol and labels") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        container = Some(Container(
          docker = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
              PortMapping(
                containerPort = 8080,
                hostPort = 0,
                servicePort = 9000,
                protocol = "tcp",
                name = Some("http"),
                labels = Map("VIP" -> "127.0.0.1:8080")),
              PortMapping(
                containerPort = 8081,
                hostPort = 0,
                servicePort = 9001,
                protocol = "udp",
                name = Some("admin"),
                labels = Map("VIP" -> "127.0.0.1:8081"))
            ))
          ))
        )))
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get

    val discoveryInfo = taskInfo.getDiscovery
    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(
        MesosProtos.Ports.newBuilder
          .addPorts(
            MesosProtos.Port.newBuilder.setName("http").setNumber(taskPorts(0)).setProtocol("tcp")
              .setLabels(MesosProtos.Labels.newBuilder().addLabels(
                MesosProtos.Label.newBuilder().setKey("VIP").setValue("127.0.0.1:8080")
              ))
          ).addPorts(
              MesosProtos.Port.newBuilder.setName("admin").setNumber(taskPorts(1)).setProtocol("udp")
                .setLabels(MesosProtos.Labels.newBuilder().addLabels(
                  MesosProtos.Label.newBuilder().setKey("VIP").setValue("127.0.0.1:8081")
                ))
            )
      ).build

    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)
  }

  test("BuildIfMatches works with duplicated resources") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    assertTaskInfo(taskInfo, taskPorts, offer)

    assert(!taskInfo.hasLabels)
  }

  test("build creates task with appropriate resource share") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    val Some((taskInfo, _)) = task

    def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

    assert(resource("cpus") == ScalarResource("cpus", 1))
    assert(resource("mem") == ScalarResource("mem", 64))
    assert(resource("disk") == ScalarResource("disk", 1))
    val portsResource: Resource = resource("ports")
    assert(portsResource.getRanges.getRangeList.asScala.map(range => range.getEnd - range.getBegin + 1).sum == 2)
    assert(portsResource.getRole == ResourceRole.Unreserved)
  }

  // #1583 Do not pass zero disk resource shares to Mesos
  test("build does set disk resource to zero in TaskInfo") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        disk = 0.0
      )
    )

    val Some((taskInfo, _)) = task

    def resourceOpt(name: String) = taskInfo.getResourcesList.asScala.find(_.getName == name)

    assert(resourceOpt("disk").isEmpty)
  }

  test("build creates task with appropriate resource share also preserves role") {
    val offer = MarathonTestHelper.makeBasicOffer(
      cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000, role = "marathon"
    ).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      ),
      mesosRole = Some("marathon"),
      acceptedResourceRoles = Some(Set(ResourceRole.Unreserved, "marathon"))
    )

    val Some((taskInfo, _)) = task

    def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

    assert(resource("cpus") == ScalarResource("cpus", 1, "marathon"))
    assert(resource("mem") == ScalarResource("mem", 64, "marathon"))
    assert(resource("disk") == ScalarResource("disk", 1, "marathon"))
    val portsResource: Resource = resource("ports")
    assert(portsResource.getRanges.getRangeList.asScala.map(range => range.getEnd - range.getBegin + 1).sum == 2)
    assert(portsResource.getRole == "marathon")
  }

  test("build creates task for DOCKER container using named, external [DockerVolume] volumes") {
    val offer = MarathonTestHelper.makeBasicOffer(
      cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
    ).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 32.0,
        executor = "//cmd",
        portDefinitions = Nil,
        container = Some(Container(
          docker = Some(Docker()), // must have this to force docker container serialization
          `type` = MesosProtos.ContainerInfo.Type.DOCKER,
          volumes = Seq[Volume](
            DockerVolume("/container/path", "namedFoo", MesosProtos.Volume.Mode.RW)
          )
        ))
      )
    )

    val Some((taskInfo, _)) = task
    def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
    assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

    // check protobuf construction, should be a ContainerInfo w/ volumes
    def vol(name: String): Option[MesosProtos.Volume] = {
      if (taskInfo.hasContainer) {
        taskInfo.getContainer.getVolumesList.asScala.find(_.getHostPath == name)
      }
      else None
    }

    assert(taskInfo.getContainer.getVolumesList.size > 0, "check that container has volumes declared")
    assert(!taskInfo.getContainer.getDocker.hasVolumeDriver, "docker spec should not define a volume driver")
    assert(vol("namedFoo").isDefined,
      s"missing expected volume namedFoo, got instead: ${taskInfo.getContainer.getVolumesList}")
  }

  // TODO(jdef) test both dockerhostvol and persistent extvol in the same docker container

  test("build creates task for DOCKER container using external [DockerVolume] volumes") {
    val offer = MarathonTestHelper.makeBasicOffer(
      cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
    ).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 32.0,
        executor = "//cmd",
        portDefinitions = Nil,
        container = Some(Container(
          `type` = MesosProtos.ContainerInfo.Type.DOCKER,
          docker = Some(Docker()), // must have this to force docker container serialization
          volumes = Seq[Volume](
            ExternalVolume("/container/path", ExternalVolumeInfo(
              name = "namedFoo",
              provider = "dvdi",
              options = Map[String, String]("dvdi/driver" -> "bar")
            ), MesosProtos.Volume.Mode.RW),
            ExternalVolume("/container/path2", ExternalVolumeInfo(
              name = "namedEdc",
              provider = "dvdi",
              options = Map[String, String]("dvdi/driver" -> "ert")
            ), MesosProtos.Volume.Mode.RO)
          )
        ))
      )
    )

    val Some((taskInfo, _)) = task
    def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
    assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

    // check protobuf construction, should be a ContainerInfo w/ volumes
    def vol(name: String): Option[MesosProtos.Volume] = {
      if (taskInfo.hasContainer) {
        taskInfo.getContainer.getVolumesList.asScala.find(_.getHostPath == name)
      }
      else None
    }

    assert(taskInfo.getContainer.getVolumesList.size > 0, "check that container has volumes declared")
    assert(taskInfo.getContainer.getDocker.hasVolumeDriver, "docker spec should define a volume driver")
    assert(taskInfo.getContainer.getDocker.getVolumeDriver == "ert", "docker spec should choose ert driver")
    assert(vol("namedFoo").isDefined,
      s"missing expected volume namedFoo, got instead: ${taskInfo.getContainer.getVolumesList}")
    assert(vol("namedEdc").isDefined,
      s"missing expected volume namedFoo, got instead: ${taskInfo.getContainer.getVolumesList}")
  }

  test("build creates task for MESOS container using named, external [ExternalVolume] volumes") {
    val offer = MarathonTestHelper.makeBasicOffer(
      cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
    ).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 32.0,
        executor = "/qazwsx",
        portDefinitions = Nil,
        container = Some(Container(
          `type` = MesosProtos.ContainerInfo.Type.MESOS,
          volumes = Seq[Volume](
            ExternalVolume("/container/path", ExternalVolumeInfo(
              name = "namedFoo",
              provider = "dvdi",
              options = Map[String, String]("dvdi/driver" -> "bar")
            ), MesosProtos.Volume.Mode.RW),
            ExternalVolume("/container/path2", ExternalVolumeInfo(
              size = Some(2L),
              name = "namedEdc",
              provider = "dvdi",
              options = Map[String, String]("dvdi/driver" -> "ert")
            ), MesosProtos.Volume.Mode.RW)
          )
        ))
      )
    )

    val Some((taskInfo, _)) = task
    def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
    assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

    def hasEnv(name: String, value: String): Boolean =
      taskInfo.getExecutor.getCommand.hasEnvironment &&
        taskInfo.getExecutor.getCommand.getEnvironment.getVariablesList.asScala.find{ ev =>
          ev.getName == name && ev.getValue == value
        }.isDefined

    def missingEnv(name: String): Boolean =
      taskInfo.getExecutor.getCommand.hasEnvironment &&
        taskInfo.getExecutor.getCommand.getEnvironment.getVariablesList.asScala.find{ ev =>
          ev.getName == name
        }.isEmpty

    taskInfo.hasContainer should be (false)
    taskInfo.hasCommand should be (false)
    taskInfo.getExecutor.hasContainer should be (true)
    taskInfo.getExecutor.getContainer.hasMesos should be (true)

    // check protobuf construction, should be a ContainerInfo w/ no volumes, w/ envvar
    assert(taskInfo.getExecutor.getContainer.getVolumesList.isEmpty, "check that container has no volumes declared")
    assert(hasEnv("DVDI_VOLUME_NAME", "namedFoo"),
      s"missing expected command w/ envvar declaring volume namedFoo, got instead: ${taskInfo.getExecutor.getCommand}")
    assert(hasEnv("DVDI_VOLUME_DRIVER", "bar"),
      s"missing expected command w/ envvar declaring volume namedFoo, got instead: ${taskInfo.getExecutor.getCommand}")
    assert(missingEnv("DVDI_VOLUME_OPTS"),
      s"has unexpected command w/ envvar declaring volume namedFoo, got instead: ${taskInfo.getExecutor.getCommand}")

    assert(hasEnv("DVDI_VOLUME_NAME1", "namedEdc"),
      s"missing expected command w/ envvar declaring volume namedEdc, got instead: ${taskInfo.getExecutor.getCommand}")
    assert(hasEnv("DVDI_VOLUME_DRIVER1", "ert"),
      s"missing expected command w/ envvar declaring volume namedEdc, got instead: ${taskInfo.getExecutor.getCommand}")
    assert(hasEnv("DVDI_VOLUME_OPTS1", "size=2"),
      s"missing expected command w/ envvar declaring volume namedEdc, got instead: ${taskInfo.getExecutor.getCommand}")

    assert(missingEnv("DVDI_VOLUME_NAME2"),
      s"has unexpected command w/ envvar declaring volume namedFoo, got instead: ${taskInfo.getExecutor.getCommand}")
  }

  test("BuildIfMatchesWithLabels") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val labels = Map("foo" -> "bar", "test" -> "test")

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081),
        labels = labels
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    assertTaskInfo(taskInfo, taskPorts, offer)

    val expectedLabels = MesosProtos.Labels.newBuilder.addAllLabels(
      labels.map {
        case (mKey, mValue) =>
          MesosProtos.Label.newBuilder.setKey(mKey).setValue(mValue).build()
      }.asJava
    ).build()
    assert(taskInfo.hasLabels)
    assert(taskInfo.getLabels == expectedLabels)
  }

  test("BuildIfMatchesWithArgs") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val rangeResourceOpt = taskInfo.getResourcesList.asScala.find(r => r.getName == Resource.PORTS)
    val ranges = rangeResourceOpt.fold(Seq.empty[MesosProtos.Value.Range])(_.getRanges.getRangeList.asScala.to[Seq])
    val rangePorts = ranges.flatMap(r => r.getBegin to r.getEnd).toSet
    assert(2 == rangePorts.size)
    assert(2 == taskPorts.size)
    assert(taskPorts.toSet == rangePorts.toSet)

    assert(!taskInfo.hasExecutor)
    assert(taskInfo.hasCommand)
    val cmd = taskInfo.getCommand
    assert(!cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.asScala == Seq("a", "b", "c"))

    for (r <- taskInfo.getResourcesList.asScala) {
      assert(ResourceRole.Unreserved == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithIpAddress") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        portDefinitions = Nil,
        ipAddress = Some(
          IpAddress(
            groups = Seq("a", "b", "c"),
            labels = Map(
              "foo" -> "bar",
              "baz" -> "buzz"
            )
          )
        )
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get

    taskInfo.hasExecutor should be (false)
    taskInfo.hasContainer should be (true)

    val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala
    networkInfos.size should be (1)

    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
      .addAllGroups(Seq("a", "b", "c").asJava)
      .setLabels(
        MesosProtos.Labels.newBuilder.addAllLabels(
          Seq(
            MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
            MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
          ).asJava
        ))
      .build
    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
    networkInfos.head should equal(networkInfoProto)
  }

  test("BuildIfMatchesWithIpAddressAndCustomExecutor") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "/custom/executor",
        portDefinitions = Nil,
        ipAddress = Some(
          IpAddress(
            groups = Seq("a", "b", "c"),
            labels = Map(
              "foo" -> "bar",
              "baz" -> "buzz"
            )
          )
        )
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get

    taskInfo.hasContainer should be (false)
    taskInfo.hasExecutor should be (true)
    taskInfo.getExecutor.hasContainer should be (true)

    val networkInfos = taskInfo.getExecutor.getContainer.getNetworkInfosList.asScala
    networkInfos.size should be (1)

    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
      .addAllGroups(Seq("a", "b", "c").asJava)
      .setLabels(
        MesosProtos.Labels.newBuilder.addAllLabels(
          Seq(
            MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
            MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
          ).asJava
        ))
      .build
    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
    networkInfos.head should equal(networkInfoProto)

    taskInfo.hasDiscovery should be (true)
    taskInfo.getDiscovery.getName should be (taskInfo.getName)
  }

  test("BuildIfMatchesWithIpAddressAndDiscoveryInfo") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        portDefinitions = Nil,
        ipAddress = Some(
          IpAddress(
            groups = Seq("a", "b", "c"),
            labels = Map(
              "foo" -> "bar",
              "baz" -> "buzz"
            ),
            discoveryInfo = DiscoveryInfo(
              ports = Seq(DiscoveryInfo.Port(name = "http", number = 80, protocol = "tcp"))
            )
          )
        )
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get

    taskInfo.hasExecutor should be (false)
    taskInfo.hasContainer should be (true)

    val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala
    networkInfos.size should be (1)

    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
      .addAllGroups(Seq("a", "b", "c").asJava)
      .setLabels(
        MesosProtos.Labels.newBuilder.addAllLabels(
          Seq(
            MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
            MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
          ).asJava
        ))
      .build
    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
    networkInfos.head should equal(networkInfoProto)

    taskInfo.hasDiscovery should be (true)
    val discoveryInfo = taskInfo.getDiscovery

    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(
        MesosProtos.Ports.newBuilder
          .addPorts(
            MesosProtos.Port.newBuilder
              .setName("http")
              .setNumber(80)
              .setProtocol("tcp")
              .build)
          .build)
      .build
    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)
  }

  test("BuildIfMatchesWithCommandAndExecutor") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        cmd = Some("foo"),
        executor = "/custom/executor",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, _) = task.get
    assert(taskInfo.hasExecutor)
    assert(!taskInfo.hasCommand)

    val cmd = taskInfo.getExecutor.getCommand
    assert(cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.asScala.isEmpty)
    assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' foo")
  }

  test("BuildIfMatchesWithArgsAndExecutor") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        args = Some(Seq("a", "b", "c")),
        executor = "/custom/executor",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, _) = task.get
    val cmd = taskInfo.getExecutor.getCommand

    assert(!taskInfo.hasCommand)
    assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' a b c")
  }

  test("BuildIfMatchesWithRole") {
    val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "marathon")
      .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
      .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
      .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(ScalarResource("disk", 2000, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 2.0,
        mem = 200.0,
        disk = 2.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      ),
      mesosRole = Some("marathon"),
      acceptedResourceRoles = Some(Set("marathon"))
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val ports = taskInfo.getResourcesList.asScala
      .find(r => r.getName == Resource.PORTS)
      .map(r => r.getRanges.getRangeList.asScala.flatMap(range => range.getBegin to range.getEnd))
      .getOrElse(Seq.empty)
    assert(ports == taskPorts)

    for (r <- taskInfo.getResourcesList.asScala) {
      assert("marathon" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithRole2") {
    val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = ResourceRole.Unreserved)
      .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
      .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
      .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(ScalarResource("disk", 2000, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        portDefinitions = PortDefinitions(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val ports = taskInfo.getResourcesList.asScala
      .find(r => r.getName == Resource.PORTS)
      .map(r => r.getRanges.getRangeList.asScala.flatMap(range => range.getBegin to range.getEnd))
      .getOrElse(Seq.empty)
    assert(ports == taskPorts)

    // In this case, the first roles are sufficient so we'll use those first.
    for (r <- taskInfo.getResourcesList.asScala) {
      assert(ResourceRole.Unreserved == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("PortMappingsWithZeroContainerPort") {
    val offer = MarathonTestHelper.makeBasicOfferWithRole(
      cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31000, role = ResourceRole.Unreserved
    )
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer, AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        container = Some(Container(
          docker = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
              PortMapping(containerPort = 0, hostPort = 0, servicePort = 9000, protocol = "tcp")
            ))
          ))
        ))
      )
    )
    assert(task.isDefined)
    val (taskInfo, _) = task.get
    val hostPort = taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort
    assert(hostPort == 31000)
    val containerPort = taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort
    assert(containerPort == hostPort)
  }

  test("BuildIfMatchesWithRackIdConstraint") {
    val offer = MarathonTestHelper.makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addAttributes(TextAttribute("rackid", "1"))
      .build

    val app = MarathonTestHelper.makeBasicApp().copy(
      constraints = Set(
        Protos.Constraint.newBuilder
          .setField("rackid")
          .setOperator(Protos.Constraint.Operator.UNIQUE)
          .build()
      )
    )

    val t1 = makeSampleTask(app.id, "rackid", "2")
    val t2 = makeSampleTask(app.id, "rackid", "3")
    val s = Set(t1, t2)

    val builder = new TaskBuilder(app,
      s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

    val task = builder.buildIfMatches(offer, s)

    assert(task.isDefined)
    // TODO test for resources etc.
  }

  test("RackAndHostConstraints") {
    // Test the case where we want tasks to be balanced across racks/AZs
    // and run only one per machine
    val app = MarathonTestHelper.makeBasicApp().copy(
      instances = 10,
      versionInfo = OnlyVersion(Timestamp(10)),
      constraints = Set(
        Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
        Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
      )
    )

    var runningTasks = Set.empty[Task]

    val builder = new TaskBuilder(app,
      s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

    def shouldBuildTask(message: String, offer: Offer) {
      val Some((taskInfo, ports)) = builder.buildIfMatches(offer, runningTasks)
      val marathonTask = MarathonTestHelper.makeTaskFromTaskInfo(taskInfo, offer)
      runningTasks += marathonTask
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer, runningTasks)
      assert(tupleOption.isEmpty, message)
    }

    val offerRack1HostA = MarathonTestHelper.makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(TextAttribute("rackid", "1"))
      .build
    shouldBuildTask("Should take first offer", offerRack1HostA)

    val offerRack1HostB = MarathonTestHelper.makeBasicOffer()
      .setHostname("beta")
      .addAttributes(TextAttribute("rackid", "1"))
      .build
    shouldNotBuildTask("Should not take offer for the same rack", offerRack1HostB)

    val offerRack2HostC = MarathonTestHelper.makeBasicOffer()
      .setHostname("gamma")
      .addAttributes(TextAttribute("rackid", "2"))
      .build
    shouldBuildTask("Should take offer for different rack", offerRack2HostC)

    // Nothing prevents having two hosts with the same name in different racks
    val offerRack3HostA = MarathonTestHelper.makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(TextAttribute("rackid", "3"))
      .build
    shouldNotBuildTask("Should not take offer in different rack with non-unique hostname", offerRack3HostA)
  }

  test("UniqueHostNameAndClusterAttribute") {
    val app = MarathonTestHelper.makeBasicApp().copy(
      instances = 10,
      constraints = Set(
        Protos.Constraint.newBuilder.setField("spark").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("enabled").build,
        Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
      )
    )

    var runningTasks = Set.empty[Task]

    val builder = new TaskBuilder(app,
      s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

    def shouldBuildTask(message: String, offer: Offer) {
      val Some((taskInfo, ports)) = builder.buildIfMatches(offer, runningTasks)
      val marathonTask = MarathonTestHelper.makeTaskFromTaskInfo(taskInfo, offer)
      runningTasks += marathonTask
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer, runningTasks)
      assert(tupleOption.isEmpty, message)
    }

    val offerHostA = MarathonTestHelper.makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(TextAttribute("spark", "disabled"))
      .build
    shouldNotBuildTask("Should not take an offer with spark:disabled", offerHostA)

    val offerHostB = MarathonTestHelper.makeBasicOffer()
      .setHostname("beta")
      .addAttributes(TextAttribute("spark", "enabled"))
      .build
    shouldBuildTask("Should take offer with spark:enabled", offerHostB)
  }

  test("PortsEnv") {
    val env = TaskBuilder.portsEnv(Seq(0, 0), Seq(1001, 1002))
    assert("1001" == env("PORT"))
    assert("1001" == env("PORT0"))
    assert("1002" == env("PORT1"))
    assert(!env.contains("PORT_0"))
  }

  test("PortsEnvEmpty") {
    val env = TaskBuilder.portsEnv(Seq(), Seq())
    assert(Map.empty == env)
  }

  test("DeclaredPortsEnv") {
    val env = TaskBuilder.portsEnv(Seq(80, 8080), Seq(1001, 1002))
    assert("1001" == env("PORT"))
    assert("1001" == env("PORT0"))
    assert("1002" == env("PORT1"))

    assert("1001" == env("PORT_80"))
    assert("1002" == env("PORT_8080"))
  }

  test("TaskContextEnv empty when no taskId given") {
    val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
    val app = AppDefinition(
      id = PathId("/app"),
      versionInfo = version
    )
    val env = TaskBuilder.taskContextEnv(app = app, taskId = None)

    assert(env == Map.empty)
  }

  test("TaskContextEnv minimal") {
    val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
    val app = AppDefinition(
      id = PathId("/app"),
      versionInfo = version
    )
    val env = TaskBuilder.taskContextEnv(app = app, taskId = Some(Task.Id("taskId")))

    assert(
      env == Map(
        "MESOS_TASK_ID" -> "taskId",
        "MARATHON_APP_ID" -> "/app",
        "MARATHON_APP_VERSION" -> "2015-02-03T12:30:00.000Z",
        "MARATHON_APP_RESOURCE_CPUS" -> AppDefinition.DefaultCpus.toString,
        "MARATHON_APP_RESOURCE_MEM" -> AppDefinition.DefaultMem.toString,
        "MARATHON_APP_RESOURCE_DISK" -> AppDefinition.DefaultDisk.toString,
        "MARATHON_APP_LABELS" -> ""
      )
    )
  }

  test("TaskContextEnv all fields") {
    val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
    val taskId = TaskID("taskId")
    val app = AppDefinition(
      id = PathId("/app"),
      versionInfo = version,
      container = Some(Container(
        docker = Some(Docker(
          image = "myregistry/myimage:version"
        ))
      )),
      cpus = 10.0,
      mem = 256.0,
      disk = 128.0,
      labels = Map(
        "LABEL1" -> "VALUE1",
        "LABEL2" -> "VALUE2"
      )
    )
    val env = TaskBuilder.taskContextEnv(app = app, Some(Task.Id(taskId)))

    assert(
      env == Map(
        "MESOS_TASK_ID" -> "taskId",
        "MARATHON_APP_ID" -> "/app",
        "MARATHON_APP_VERSION" -> "2015-02-03T12:30:00.000Z",
        "MARATHON_APP_DOCKER_IMAGE" -> "myregistry/myimage:version",
        "MARATHON_APP_RESOURCE_CPUS" -> "10.0",
        "MARATHON_APP_RESOURCE_MEM" -> "256.0",
        "MARATHON_APP_RESOURCE_DISK" -> "128.0",
        "MARATHON_APP_LABELS" -> "LABEL1 LABEL2",
        "MARATHON_APP_LABEL_LABEL1" -> "VALUE1",
        "MARATHON_APP_LABEL_LABEL2" -> "VALUE2"
      )
    )
  }

  test("TaskContextEnv will provide label env safety") {

    // will exceed max length for sure
    val longLabel = "longlabel" * TaskBuilder.maxVariableLength
    var longValue = "longvalue" * TaskBuilder.maxEnvironmentVarLength

    val app = AppDefinition(
      labels = Map(
        "label" -> "VALUE1",
        "label-with-invalid-chars" -> "VALUE2",
        "other--label\\--\\a" -> "VALUE3",
        longLabel -> "value for long label",
        "label-long" -> longValue
      )
    )

    val env = TaskBuilder.taskContextEnv(app = app, Some(Task.Id("taskId")))
      .filterKeys(_.startsWith("MARATHON_APP_LABEL"))

    assert(
      env == Map(
        "MARATHON_APP_LABELS" -> "OTHER_LABEL_A LABEL LABEL_WITH_INVALID_CHARS",
        "MARATHON_APP_LABEL_LABEL" -> "VALUE1",
        "MARATHON_APP_LABEL_LABEL_WITH_INVALID_CHARS" -> "VALUE2",
        "MARATHON_APP_LABEL_OTHER_LABEL_A" -> "VALUE3"
      )
    )
  }

  test("AppContextEnvironment") {
    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          id = "/test".toPath,
          portDefinitions = PortDefinitions(8080, 8081),
          container = Some(Container(
            docker = Some(Docker(
              image = "myregistry/myimage:version"
            ))
          )
          )
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        ports = Seq(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("task-123" == env("MESOS_TASK_ID"))
    assert("/test" == env("MARATHON_APP_ID"))
    assert("1970-01-01T00:00:00.000Z" == env("MARATHON_APP_VERSION"))
    assert("myregistry/myimage:version" == env("MARATHON_APP_DOCKER_IMAGE"))
  }

  test("user defined variables override automatic port variables") {
    // why?
    // see https://github.com/mesosphere/marathon/issues/905

    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          id = "/test".toPath,
          portDefinitions = PortDefinitions(8080, 8081),
          env = Map(
            "PORT" -> "1",
            "PORTS" -> "ports",
            "PORT0" -> "1",
            "PORT1" -> "2",
            "PORT_8080" -> "port8080",
            "PORT_8081" -> "port8081"
          )
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        ports = Seq(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("1" == env("PORT"))
    assert("ports" == env("PORTS"))
    assert("1" == env("PORT0"))
    assert("2" == env("PORT1"))
    assert("port8080" == env("PORT_8080"))
    assert("port8081" == env("PORT_8081"))
  }

  test("PortsEnvWithOnlyPorts") {
    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        ports = Seq(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("1000" == env("PORT_8080"))
    assert("1001" == env("PORT_8081"))
  }

  test("PortsEnvWithCustomPrefix") {
    val command =
      TaskBuilder.commandInfo(
        AppDefinition(
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        Some(Task.Id("task-123")),
        Some("host.mega.corp"),
        Seq(1000, 1001),
        Some("CUSTOM_PREFIX_")
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("1000,1001" == env("CUSTOM_PREFIX_PORTS"))

    assert("1000" == env("CUSTOM_PREFIX_PORT"))

    assert("1000" == env("CUSTOM_PREFIX_PORT0"))
    assert("1000" == env("CUSTOM_PREFIX_PORT_8080"))

    assert("1001" == env("CUSTOM_PREFIX_PORT1"))
    assert("1001" == env("CUSTOM_PREFIX_PORT_8081"))

    assert("host.mega.corp" == env("CUSTOM_PREFIX_HOST"))

    assert(Seq("HOST", "PORTS", "PORT0", "PORT1").forall(k => !env.contains(k)))
    assert(Seq("MESOS_TASK_ID", "MARATHON_APP_ID", "MARATHON_APP_VERSION").forall(env.contains))
  }

  test("OnlyWhitelistedUnprefixedVariablesWithCustomPrefix") {
    val command =
      TaskBuilder.commandInfo(
        AppDefinition(
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        Some(Task.Id("task-123")),
        Some("host.mega.corp"),
        Seq(1000, 1001),
        Some("P_")
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    val nonPrefixedEnvVars = env.filterKeys(!_.startsWith("P_"))

    val whiteList = Seq("MESOS_TASK_ID", "MARATHON_APP_ID", "MARATHON_APP_VERSION", "MARATHON_APP_RESOURCE_CPUS",
      "MARATHON_APP_RESOURCE_MEM", "MARATHON_APP_RESOURCE_DISK", "MARATHON_APP_LABELS")

    assert(nonPrefixedEnvVars.keySet.forall(whiteList.contains))
  }

  test("PortsEnvWithOnlyMappings") {
    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          container = Some(Container(
            docker = Some(Docker(
              network = Some(DockerInfo.Network.BRIDGE),
              portMappings = Some(Seq(
                PortMapping(containerPort = 8080, hostPort = 0, servicePort = 9000, protocol = "tcp"),
                PortMapping(containerPort = 8081, hostPort = 0, servicePort = 9000, protocol = "tcp")
              ))
            ))
          ))
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        ports = Seq(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("1000" == env("PORT_8080"))
    assert("1001" == env("PORT_8081"))
  }

  test("PortsEnvWithBothPortsAndMappings") {
    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          portDefinitions = PortDefinitions(22, 23),
          container = Some(Container(
            docker = Some(Docker(
              network = Some(DockerInfo.Network.BRIDGE),
              portMappings = Some(Seq(
                PortMapping(containerPort = 8080, hostPort = 0, servicePort = 9000, protocol = "tcp"),
                PortMapping(containerPort = 8081, hostPort = 0, servicePort = 9000, protocol = "tcp")
              ))
            ))
          ))
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        ports = Seq(1000, 1001),
        envPrefix = None
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("1000" == env("PORT_8080"))
    assert("1001" == env("PORT_8081"))

    assert(!env.contains("PORT_22"))
    assert(!env.contains("PORT_23"))
  }

  test("TaskWillCopyFetchIntoCommand") {
    val command = TaskBuilder.commandInfo(
      app = AppDefinition(
        fetch = Seq(
          FetchUri(uri = "http://www.example.com", extract = false, cache = true, executable = false),
          FetchUri(uri = "http://www.example2.com", extract = true, cache = true, executable = true)
        )
      ),
      taskId = Some(Task.Id("task-123")),
      host = Some("host.mega.corp"),
      ports = Seq(1000, 1001),
      envPrefix = None
    )

    assert(command.getUris(0).getValue.contentEquals("http://www.example.com"))
    assert(command.getUris(0).getCache)
    assert(!command.getUris(0).getExtract)
    assert(!command.getUris(0).getExecutable)

    assert(command.getUris(1).getValue.contentEquals("http://www.example2.com"))
    assert(command.getUris(1).getCache)
    assert(command.getUris(1).getExtract)
    assert(command.getUris(1).getExecutable)
  }

  // #2865 Multiple explicit ports are mixed up in task json
  test("build with requirePorts preserves the port order") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        portDefinitions = PortDefinitions(25552, 25551),
        requirePorts = true
      )
    )

    val Some((taskInfo, _)) = task

    val env: Map[String, String] =
      taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    assert("25552" == env("PORT0"))
    assert("25552" == env("PORT_25552"))
    assert("25551" == env("PORT1"))
    assert("25551" == env("PORT_25551"))

    val portsFromTaskInfo = {
      val asScalaRanges = for {
        resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
        range <- resource.getRanges.getRangeList.asScala
      } yield range.getBegin to range.getEnd
      asScalaRanges.flatMap(_.iterator).toList
    }
    assert(portsFromTaskInfo == Seq(25552, 25551))
  }

  def buildIfMatches(
    offer: Offer,
    app: AppDefinition,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None) = {
    val builder = new TaskBuilder(app,
      s => Task.Id(s.toString),
      MarathonTestHelper.defaultConfig(
        mesosRole = mesosRole,
        acceptedResourceRoles = acceptedResourceRoles,
        envVarsPrefix = envVarsPrefix))

    builder.buildIfMatches(offer, Iterable.empty)
  }

  def makeSampleTask(id: PathId, attr: String, attrVal: String) = {
    import MarathonTestHelper.Implicits._
    MarathonTestHelper
      .stagedTask(taskId = id.toString)
      .withAgentInfo(_.copy(attributes = Iterable(TextAttribute(attr, attrVal))))
      .withHostPorts(Seq(999))
  }

  private def assertTaskInfo(taskInfo: MesosProtos.TaskInfo, taskPorts: Seq[Int], offer: Offer): Unit = {
    val portsFromTaskInfo = {
      val asScalaRanges = for {
        resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
        range <- resource.getRanges.getRangeList.asScala
      } yield range.getBegin to range.getEnd
      asScalaRanges.flatMap(_.iterator).toSet
    }
    assert(portsFromTaskInfo == taskPorts.toSet)

    // The taskName is the elements of the path, reversed, and joined by dots
    assert("frontend.product" == taskInfo.getName)

    assert(!taskInfo.hasExecutor)
    assert(taskInfo.hasCommand)
    val cmd = taskInfo.getCommand
    assert(cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.asScala.isEmpty)
    assert(cmd.getValue == "foo")

    assert(cmd.hasEnvironment)
    val envVars = cmd.getEnvironment.getVariablesList.asScala
    assert(envVars.exists(v => v.getName == "HOST" && v.getValue == offer.getHostname))
    assert(envVars.exists(v => v.getName == "PORT0" && v.getValue.nonEmpty))
    assert(envVars.exists(v => v.getName == "PORT1" && v.getValue.nonEmpty))
    assert(envVars.exists(v => v.getName == "PORT_8080" && v.getValue.nonEmpty))
    assert(envVars.exists(v => v.getName == "PORT_8081" && v.getValue.nonEmpty))

    val exposesFirstPort =
      envVars.find(v => v.getName == "PORT0").get.getValue == envVars.find(v => v.getName == "PORT_8080").get.getValue
    assert(exposesFirstPort)
    val exposesSecondPort =
      envVars.find(v => v.getName == "PORT1").get.getValue == envVars.find(v => v.getName == "PORT_8081").get.getValue
    assert(exposesSecondPort)

    for (r <- taskInfo.getResourcesList.asScala) {
      assert(ResourceRole.Unreserved == r.getRole)
    }

    assert(taskInfo.hasDiscovery)
    val discoveryInfo = taskInfo.getDiscovery
    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(
        MesosProtos.Ports.newBuilder
          .addPorts(MesosProtos.Port.newBuilder.setNumber(taskPorts(0)).setProtocol("tcp"))
          .addPorts(MesosProtos.Port.newBuilder.setNumber(taskPorts(1)).setProtocol("tcp"))
      ).build
    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)

    // TODO test for resources etc.
  }
}
