package mesosphere.mesos

import com.google.protobuf.TextFormat
import mesosphere.UnitTest
import mesosphere.marathon.{ Protos, _ }
import mesosphere.marathon.api.serialization.PortDefinitionSerializer
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.{ App, Resources }
import mesosphere.marathon.state.Container.{ Docker, PortMapping }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.VersionInfo.OnlyVersion
import mesosphere.marathon.state.{ AppDefinition, Container, PathId, Timestamp, _ }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.{ Resource, _ }
import org.apache.mesos.Protos.TaskInfo
import org.apache.mesos.{ Protos => MesosProtos }
import org.joda.time.{ DateTime, DateTimeZone }

import scala.concurrent.duration._

class TaskBuilderTest extends UnitTest {

  import mesosphere.mesos.protos.Implicits._

  val labels = Map("foo" -> "bar", "test" -> "test")
  val runSpecId = PathId("/test")

  "TaskBuilder" should {
    "BuildIfMatches" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      assertTaskInfo(taskInfo, networkInfo.hostPorts, offer)

      assert(!taskInfo.hasLabels)
    }

    "BuildIfMatches with port name and labels" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = Seq(
            PortDefinition(8080, "tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
            PortDefinition(8081, "tcp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
          )
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get

      val discoveryInfo = taskInfo.getDiscovery
      val expectedDiscoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
        .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
        .setName(taskInfo.getName)
        .setPorts(Helpers.mesosPorts(
          Helpers.mesosPort("http", "tcp", Map("VIP" -> "127.0.0.1:8080"), networkInfo.hostPorts.head),
          Helpers.mesosPort("admin", "tcp", Map("VIP" -> "127.0.0.1:8081"), networkInfo.hostPorts(1))
        )).build

      TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(expectedDiscoveryInfoProto))
      discoveryInfo should equal(expectedDiscoveryInfoProto)
    }

    "BuildIfMatches without port match" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 31000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(
            cpus = 1.0,
            mem = 64.0,
            disk = 1.0
          ),
          executor = "//cmd",
          portDefinitions = Seq(
            PortDefinition(8080, "tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
            PortDefinition(8081, "tcp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
          )
        )
      )

      task.isDefined should be(false)
    }

    "BuildIfMatches with port on tcp and udp" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = Seq(
            PortDefinition(8080, "udp,tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
            PortDefinition(8081, "udp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
          )
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get

      val discoveryInfo = taskInfo.getDiscovery
      val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
        .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
        .setName(taskInfo.getName)
        .setPorts(Helpers.mesosPorts(
          Helpers.mesosPort("http", "udp", Map("VIP" -> "127.0.0.1:8080"), networkInfo.hostPorts.head),
          Helpers.mesosPort("http", "tcp", Map("VIP" -> "127.0.0.1:8080"), networkInfo.hostPorts.head),
          Helpers.mesosPort("admin", "udp", Map("VIP" -> "127.0.0.1:8081"), networkInfo.hostPorts(1))
        )).build

      TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
      discoveryInfo should equal(discoveryInfoProto)
    }

    "PortDefinition to proto (zk, mesos) with tcp, udp protocol" in {
      val portDefinition = PortDefinition(port = 80, protocol = "udp,tcp")

      // used for mesos communication, should return two ports
      PortDefinitionSerializer.toMesosProto(portDefinition).size should be(2)
      // used for zk communication, should return only one port with "udp,tcp" as protocol name
      PortDefinitionSerializer.toProto(portDefinition).getProtocol should be("udp,tcp")
    }

    "BuildIfMatches with port name, different protocol and labels" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = Seq(
            PortDefinition(8080, "tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
            PortDefinition(8081, "udp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
          )
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get

      val discoveryInfo = taskInfo.getDiscovery
      val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
        .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
        .setName(taskInfo.getName)
        .setPorts(Helpers.mesosPorts(
          Helpers.mesosPort("http", "tcp", Map("VIP" -> "127.0.0.1:8080"), networkInfo.hostPorts.head),
          Helpers.mesosPort("admin", "udp", Map("VIP" -> "127.0.0.1:8081"), networkInfo.hostPorts(1))
        )).build

      TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
      discoveryInfo should equal(discoveryInfoProto)
    }

    "BuildIfMatches with port mapping with name, protocol and labels" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          networks = Seq(BridgeNetwork()), container = Some(Docker(

            portMappings = Seq(
              PortMapping(
                containerPort = 8080,
                hostPort = Some(0),
                servicePort = 9000,
                protocol = "tcp",
                name = Some("http"),
                labels = Map("VIP" -> "127.0.0.1:8080")
              ),
              PortMapping(
                containerPort = 8081,
                hostPort = Some(0),
                servicePort = 9001,
                protocol = "udp",
                name = Some("admin"),
                labels = Map("VIP" -> "127.0.0.1:8081")
              )
            )
          ))
        ))

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get

      val discoveryInfo = taskInfo.getDiscovery
      val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
        .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
        .setName(taskInfo.getName)
        .setPorts(Helpers.mesosPorts(
          Helpers.mesosPort("http", "tcp", Map("VIP" -> "127.0.0.1:8080", "network-scope" -> "host"), networkInfo.hostPorts.head),
          Helpers.mesosPort("admin", "udp", Map("VIP" -> "127.0.0.1:8081", "network-scope" -> "host"), networkInfo.hostPorts(1))
        )).build

      TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
      discoveryInfo should equal(discoveryInfoProto)
    }

    "BuildIfMatches with port mapping with name, protocol and labels but no host port" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          resources = Resources(
            cpus = 1.0,
            mem = 64.0,
            disk = 1.0

          ),
          executor = "//cmd",
          networks = Seq(ContainerNetwork("whatever")), container = Some(Docker(

            portMappings = Seq(
              PortMapping(
                containerPort = 8080,
                hostPort = Some(0),
                servicePort = 9000,
                protocol = "tcp",
                name = Some("http"),
                labels = Map("VIP" -> "127.0.0.1:8080")
              ),
              PortMapping(
                containerPort = 8081,
                hostPort = None,
                servicePort = 9001,
                protocol = "udp",
                name = Some("admin"),
                labels = Map("VIP" -> "127.0.0.1:8081")
              )
            )
          ))
        ))

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get

      val discoveryInfo = taskInfo.getDiscovery
      val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
        .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
        .setName(taskInfo.getName)
        .setPorts(Helpers.mesosPorts(
          Helpers.mesosPort("http", "tcp", Map("VIP" -> "127.0.0.1:8080", "network-scope" -> "host"), networkInfo.hostPorts.head),
          Helpers.mesosPort("admin", "udp", Map("VIP" -> "127.0.0.1:8081", "network-scope" -> "container"), 8081)
        )).build

      TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
      discoveryInfo should equal(discoveryInfoProto)
    }

    "BuildIfMatches works with duplicated resources" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
        .addResources(ScalarResource("cpus", 1))
        .addResources(ScalarResource("mem", 128))
        .addResources(ScalarResource("disk", 2000))
        .build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      assertTaskInfo(taskInfo, networkInfo.hostPorts, offer)

      assert(!taskInfo.hasLabels)
    }

    "build creates task with appropriate resource share" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      val Some((taskInfo: TaskInfo, _)) = task

      def resource(name: String): Resource = taskInfo.getResourcesList.find(_.getName == name).get

      assert(resource("cpus") == ScalarResource("cpus", 1))
      assert(resource("mem") == ScalarResource("mem", 64))
      assert(resource("disk") == ScalarResource("disk", 1))
      val portsResource: Resource = resource("ports")
      assert(portsResource.getRanges.getRangeList.map(range => range.getEnd - range.getBegin + 1).sum == 2)
      assert(portsResource.getRole == ResourceRole.Unreserved)
    }

    // #1583 Do not pass zero disk resource shares to Mesos
    "build does set disk resource to zero in TaskInfo" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(disk = 0.0)
        )
      )

      val Some((taskInfo: TaskInfo, _)) = task

      def resourceOpt(name: String) = taskInfo.getResourcesList.find(_.getName == name)

      assert(resourceOpt("disk").isEmpty)
    }

    "build creates task with appropriate resource share also preserves role" in {
      val offer = MarathonTestHelper.makeBasicOffer(
        cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000, role = "marathon"
      ).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        mesosRole = Some("marathon"),
        acceptedResourceRoles = Some(Set(ResourceRole.Unreserved, "marathon"))
      )

      val Some((taskInfo: TaskInfo, _)) = task

      def resource(name: String): Resource = taskInfo.getResourcesList.find(_.getName == name).get

      assert(resource("cpus") == ScalarResource("cpus", 1, "marathon"))
      assert(resource("mem") == ScalarResource("mem", 64, "marathon"))
      assert(resource("disk") == ScalarResource("disk", 1, "marathon"))
      val portsResource: Resource = resource("ports")
      assert(portsResource.getRanges.getRangeList.map(range => range.getEnd - range.getBegin + 1).sum == 2)
      assert(portsResource.getRole == "marathon")
    }

    "build creates task for DOCKER container using relative hostPath" in {
      val offer = MarathonTestHelper.makeBasicOffer(
        cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
      ).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 32.0),
          executor = "//cmd",
          portDefinitions = Nil,
          container = Some(Docker(
            volumes = Seq[Volume](
              DockerVolume("/container/path", "relativeDirName", MesosProtos.Volume.Mode.RW)
            )
          ))
        )
      )

      val Some((taskInfo: TaskInfo, _)) = task

      def resource(name: String): Resource = taskInfo.getResourcesList.find(_.getName == name).get

      assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

      // check protobuf construction, should be a ContainerInfo w/ volumes
      def vol(path: String): Option[MesosProtos.Volume] = {
        if (taskInfo.hasContainer) {
          taskInfo.getContainer.getVolumesList.find(_.getHostPath == path)
        } else None
      }

      assert(taskInfo.getContainer.getVolumesList.size > 0, "check that container has volumes declared")
      assert(
        vol("relativeDirName").isDefined,
        s"missing expected volume relativeDirName, got instead: ${taskInfo.getContainer.getVolumesList}")
    }

    "build creates task for DOCKER container using host-local and external [DockerVolume] volumes" in {
      import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProviderVolumeToUnifiedMesosVolumeTest._
      val offer = MarathonTestHelper.makeBasicOffer(
        cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
      ).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 32.0),
          executor = "//cmd",
          portDefinitions = Nil,
          container = Some(Docker(
            volumes = Seq[Volume](
              ExternalVolume("/container/path", ExternalVolumeInfo(
                name = "namedFoo",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "bar")
              ), MesosProtos.Volume.Mode.RW),
              DockerVolume("/container/path", "relativeDirName", MesosProtos.Volume.Mode.RW)
            )
          ))
        )
      )

      val Some((taskInfo: TaskInfo, _)) = task

      def resource(name: String): Resource = taskInfo.getResourcesList.find(_.getName == name).get

      assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

      // check protobuf construction, should be a ContainerInfo w/ volumes
      def vol(name: String): Option[MesosProtos.Volume] = {
        if (taskInfo.hasContainer) {
          taskInfo.getContainer.getVolumesList.find(_.getHostPath == name)
        } else None
      }

      assert(taskInfo.getContainer.getVolumesList.size == 2, "check that container has volumes declared")

      assert(
        taskInfo.getContainer.getVolumesList.size == 2,
        s"check that container has 2 volumes declared, got instead ${taskInfo.getExecutor.getContainer.getVolumesList}")

      val vol1 = volumeWith(
        containerPath("/container/path"),
        mode(MesosProtos.Volume.Mode.RW),
        volumeRef("bar", "namedFoo")
      )

      val got1 = taskInfo.getContainer.getVolumes(0)
      assert(vol1.equals(got1), s"expected volume $vol1, got instead: $got1")

      assert(
        vol("relativeDirName").isDefined,
        s"missing expected volume relativeDirName, got instead: ${taskInfo.getContainer.getVolumesList}")
    }

    "build creates task for DOCKER container using external [DockerVolume] volumes" in {
      import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProviderVolumeToUnifiedMesosVolumeTest._
      val offer = MarathonTestHelper.makeBasicOffer(
        cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
      ).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 32.0),
          executor = "//cmd",
          portDefinitions = Nil,
          container = Some(Docker(
            volumes = Seq[Volume](
              ExternalVolume("/container/path", ExternalVolumeInfo(
                name = "namedFoo",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "bar")
              ), MesosProtos.Volume.Mode.RW),
              ExternalVolume("/container/path2", ExternalVolumeInfo(
                name = "namedEdc",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "ert", "dvdi/boo" -> "baa")
              ), MesosProtos.Volume.Mode.RO)
            )
          ))
        )
      )

      val Some((taskInfo: TaskInfo, _)) = task

      def resource(name: String): Resource = taskInfo.getResourcesList.find(_.getName == name).get

      assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

      assert(taskInfo.getContainer.getVolumesList.size == 2, "check that container has volumes declared")

      // check protobuf construction, should be a ContainerInfo w/ no volumes, w/ envvar
      assert(
        taskInfo.getContainer.getVolumesList.size == 2,
        s"check that container has 2 volumes declared, got instead ${taskInfo.getExecutor.getContainer.getVolumesList}")

      val vol1 = volumeWith(
        containerPath("/container/path"),
        mode(MesosProtos.Volume.Mode.RW),
        volumeRef("bar", "namedFoo")
      )

      val got1 = taskInfo.getContainer.getVolumes(0)
      assert(vol1.equals(got1), s"expected volume $vol1, got instead: $got1")

      val vol2 = volumeWith(
        containerPath("/container/path2"),
        mode(MesosProtos.Volume.Mode.RO),
        volumeRef("ert", "namedEdc"),
        options(Map("boo" -> "baa"))
      )
      val got2 = taskInfo.getContainer.getVolumes(1)
      assert(vol2.equals(got2), s"expected volume $vol2, got instead: $got2")
    }

    "build creates task for MESOS container using named, external [ExternalVolume] volumes" in {
      import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProviderVolumeToUnifiedMesosVolumeTest._
      val offer = MarathonTestHelper.makeBasicOffer(
        cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000
      ).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 32.0),
          executor = "/qazwsx",
          portDefinitions = Nil,
          container = Some(Container.Mesos(
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

      val Some((taskInfo: TaskInfo, _)) = task

      def resource(name: String): Resource = taskInfo.getResourcesList.find(_.getName == name).get

      assert(resource("cpus") == ScalarResource("cpus", 1)) // sanity, we DID match the offer, right?

      taskInfo.hasContainer should be(false)
      taskInfo.hasCommand should be(false)
      taskInfo.getExecutor.hasContainer should be(true)
      taskInfo.getExecutor.getContainer.hasMesos should be(true)

      // check protobuf construction, should be a ContainerInfo w/ no volumes, w/ envvar
      assert(
        taskInfo.getExecutor.getContainer.getVolumesList.size == 2,
        s"check that container has 2 volumes declared, got instead ${taskInfo.getExecutor.getContainer.getVolumesList}")

      val vol1 = volumeWith(
        containerPath("/container/path"),
        mode(MesosProtos.Volume.Mode.RW),
        volumeRef("bar", "namedFoo")
      )
      val got1 = taskInfo.getExecutor.getContainer.getVolumes(0)
      assert(vol1.equals(got1), s"expected volume $vol1, got instead: $got1")

      val vol2 = volumeWith(
        containerPath("/container/path2"),
        mode(MesosProtos.Volume.Mode.RW),
        volumeRef("ert", "namedEdc"),
        options(Map("size" -> "2"))
      )
      val got2 = taskInfo.getExecutor.getContainer.getVolumes(1)
      assert(vol2.equals(got2), s"expected volume $vol2, got instead: $got2")
    }

    "build creates task for MESOS Docker container" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer, AppDefinition(
        id = "/testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        container = Some(Container.MesosDocker(
          image = "busybox",
          credential = Some(Container.Credential(
            principal = "aPrincipal",
            secret = Some("aSecret")
          ))
        )),
        portDefinitions = Seq.empty,
        networks = Seq(ContainerNetwork("vnet"))
      )
      )
      assert(task.isDefined, "expected task to match offer")
      val (taskInfo: TaskInfo, _) = task.get
      taskInfo.hasContainer should be (true)
      taskInfo.getContainer.getType should be (MesosProtos.ContainerInfo.Type.MESOS)
      taskInfo.getContainer.hasMesos should be (true)
      taskInfo.getContainer.getMesos.hasImage should be (true)
      taskInfo.getContainer.getMesos.getImage.getType should be (MesosProtos.Image.Type.DOCKER)
      taskInfo.getContainer.getMesos.getImage.hasDocker should be (true)
      taskInfo.getContainer.getMesos.getImage.getDocker.hasCredential should be (true)
      taskInfo.getContainer.getMesos.getImage.getDocker.getCredential.getPrincipal should be ("aPrincipal")
      taskInfo.getContainer.getMesos.getImage.getDocker.getCredential.hasSecret should be (true)
      taskInfo.getContainer.getMesos.getImage.getDocker.getCredential.getSecret should be ("aSecret")
    }

    "build creates task for MESOS AppC container" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer, AppDefinition(
        id = "/testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        container = Some(Container.MesosAppC(
          image = "anImage",
          id = Some("sha512-aHashValue"),
          labels = labels
        )),
        portDefinitions = Seq.empty,
        networks = Seq(ContainerNetwork("vnet"))
      )
      )
      assert(task.isDefined, "expected task to match offer")
      val (taskInfo: TaskInfo, _) = task.get
      taskInfo.hasContainer should be (true)
      taskInfo.getContainer.getType should be (MesosProtos.ContainerInfo.Type.MESOS)
      taskInfo.getContainer.hasMesos should be (true)
      taskInfo.getContainer.getMesos.hasImage should be (true)
      taskInfo.getContainer.getMesos.getImage.getType should be (MesosProtos.Image.Type.APPC)
      taskInfo.getContainer.getMesos.getImage.hasAppc should be (true)
      taskInfo.getContainer.getMesos.getImage.getAppc.hasId should be (true)
      taskInfo.getContainer.getMesos.getImage.getAppc.getId should be ("sha512-aHashValue")
      taskInfo.getContainer.getMesos.getImage.getAppc.hasLabels should be (true)
      taskInfo.getContainer.getMesos.getImage.getAppc.getLabels should be (
        (labels + ("MESOS_TASK_ID" -> taskInfo.getTaskId.getValue)).toMesosLabels
      )
    }

    "BuildIfMatchesWithLabels" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081),
          labels = labels
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      assertTaskInfo(taskInfo, networkInfo.hostPorts, offer)

      assert(taskInfo.hasLabels)
      assert(taskInfo.getLabels == labels.toMesosLabels)
    }

    "BuildIfMatchesWithArgs" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/testApp".toPath,
          args = Seq("a", "b", "c"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      val rangeResourceOpt = taskInfo.getResourcesList.find(r => r.getName == Resource.PORTS)
      val ranges = rangeResourceOpt.fold(Seq.empty[MesosProtos.Value.Range])(_.getRanges.getRangeList.to[Seq])
      val rangePorts = ranges.flatMap(r => r.getBegin to r.getEnd).toSet
      assert(2 == rangePorts.size)
      assert(2 == networkInfo.hostPorts.size)
      networkInfo.hostPorts.toSet should contain theSameElementsAs rangePorts

      assert(!taskInfo.hasExecutor)
      assert(taskInfo.hasCommand)
      val cmd = taskInfo.getCommand
      assert(!cmd.getShell)
      assert(cmd.hasValue)
      cmd.getArgumentsList should contain theSameElementsInOrderAs Seq("a", "b", "c")

      for (r <- taskInfo.getResourcesList) {
        assert(ResourceRole.Unreserved == r.getRole)
      }

      // TODO test for resources etc.
    }

    "BuildIfMatchesWithoutPorts" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = Seq.empty
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      assert(networkInfo.hostPorts.isEmpty)

      val envVariables = taskInfo.getCommand.getEnvironment.getVariablesList
      assert(!envVariables.exists(v => v.getName.startsWith("PORT")))
    }

    def buildIfMatchesWithIpAddress(
      offer: MesosProtos.Offer,
      executor: String = App.DefaultExecutor,
      discoveryInfo: Seq[Container.PortMapping] = Nil,
      networkName: Option[String] = None) = buildIfMatches(
      offer,
      AppDefinition(
        id = "/testApp".toPath,
        args = Seq("a", "b", "c"),
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        portDefinitions = Nil,
        executor = executor,
        networks = Seq(ContainerNetwork(
          name = networkName.getOrElse("whatever"),
          labels = Map(
            "foo" -> "bar",
            "baz" -> "buzz"
          )
        )),
        container = Some(Container.Mesos(portMappings = discoveryInfo
        ))
      )
    )

    "BuildIfMatchesWithIpAddress" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatchesWithIpAddress(offer)
      assert(task.isDefined)

      val (taskInfo, _) = task.get

      taskInfo.hasExecutor should be(false)
      taskInfo.hasContainer should be(true)

      val networkInfos = taskInfo.getContainer.getNetworkInfosList
      networkInfos.size should be(1)

      val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
        .setName("whatever")
        .setLabels(
          Map("foo" -> "bar", "baz" -> "buzz").toMesosLabels
        )
        .build
      TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
      networkInfos.head should equal(networkInfoProto)
    }

    "BuildIfMatchesWithIpAddressAndCustomExecutor" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatchesWithIpAddress(offer, executor = "/custom/executor")

      assert(task.isDefined)

      val (taskInfo, _) = task.get

      taskInfo.hasContainer should be(false)
      taskInfo.hasExecutor should be(true)
      taskInfo.getExecutor.hasContainer should be(true)

      val networkInfos = taskInfo.getExecutor.getContainer.getNetworkInfosList
      networkInfos.size should be(1)

      val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
        .setName("whatever")
        .setLabels(
          Map("foo" -> "bar", "baz" -> "buzz").toMesosLabels
        )
        .build
      TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
      networkInfos.head should equal(networkInfoProto)

      taskInfo.hasDiscovery should be(true)
      taskInfo.getDiscovery.getName should be(taskInfo.getName)
    }

    "BuildIfMatchesWithIpAddressAndNetworkName" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatchesWithIpAddress(offer, networkName = Some("foonet"))

      assert(task.isDefined)

      val (taskInfo, _) = task.get

      taskInfo.hasExecutor should be(false)
      taskInfo.hasContainer should be(true)

      val networkInfos = taskInfo.getContainer.getNetworkInfosList
      networkInfos.size should be(1)

      val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
        .setLabels(
          Map("foo" -> "bar", "baz" -> "buzz").toMesosLabels
        )
        .setName("foonet")
        .build
      TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
      networkInfos.head should equal(networkInfoProto)
    }

    "BuildIfMatchesWithIpAddressAndDiscoveryInfo" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatchesWithIpAddress(
        offer,
        discoveryInfo = Seq(Container.PortMapping(name = Some("http"), containerPort = 80, protocol = "tcp")
        )
      )

      assert(task.isDefined)

      val (taskInfo, _) = task.get

      taskInfo.hasExecutor should be(false)
      taskInfo.hasContainer should be(true)

      val networkInfos = taskInfo.getContainer.getNetworkInfosList
      networkInfos.size should be(1)

      val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
        .setName("whatever")
        .setLabels(
          Map("foo" -> "bar", "baz" -> "buzz").toMesosLabels
        )
        .build
      TextFormat.printToString(networkInfos.head) should equal(TextFormat.printToString(networkInfoProto))
      networkInfos.head should equal(networkInfoProto)

      taskInfo.hasDiscovery should be(true)
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
            .setLabels(Map("network-scope" -> "container").toMesosLabels)
            .build)
          .build)
        .build
      TextFormat.printToString(discoveryInfo) should equal(TextFormat.printToString(discoveryInfoProto))
      discoveryInfo should equal(discoveryInfoProto)
    }

    "BuildIfMatchesWithCommandAndExecutor" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
        .addResources(ScalarResource("cpus", 1))
        .addResources(ScalarResource("mem", 128))
        .addResources(ScalarResource("disk", 2000))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/testApp".toPath,
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          cmd = Some("foo"),
          executor = "/custom/executor",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      assert(task.isDefined)

      val (taskInfo: TaskInfo, _) = task.get
      assert(taskInfo.hasExecutor)
      assert(!taskInfo.hasCommand)

      val cmd = taskInfo.getExecutor.getCommand
      assert(cmd.getShell)
      assert(cmd.hasValue)
      assert(cmd.getArgumentsList.isEmpty)
      assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' foo")
    }

    "BuildIfMatchesWithArgsAndExecutor" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/testApp".toPath,
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          args = Seq("a", "b", "c"),
          executor = "/custom/executor",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      assert(task.isDefined)

      val (taskInfo: TaskInfo, _) = task.get
      val cmd = taskInfo.getExecutor.getCommand

      assert(!taskInfo.hasCommand)
      assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' a b c")
    }

    "BuildIfMatchesWithRole" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "marathon")
        .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
        .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
        .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
        .addResources(ScalarResource("cpus", 2, "marathon"))
        .addResources(ScalarResource("mem", 256, "marathon"))
        .addResources(ScalarResource("disk", 2000, "marathon"))
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/testApp".toPath,
          resources = Resources(cpus = 2.0, mem = 200.0, disk = 2.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        ),
        mesosRole = Some("marathon"),
        acceptedResourceRoles = Some(Set("marathon"))
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      val ports = taskInfo.getResourcesList
        .find(r => r.getName == Resource.PORTS)
        .map(r => r.getRanges.getRangeList.flatMap(range => range.getBegin to range.getEnd))
        .getOrElse(Seq.empty)
      assert(ports == networkInfo.hostPorts) // linter:ignore:UnlikelyEquality

      for (r <- taskInfo.getResourcesList) {
        assert("marathon" == r.getRole)
      }

      // TODO test for resources etc.
    }

    "BuildIfMatchesWithRole2" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = ResourceRole.Unreserved)
        .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
        .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
        .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
        .addResources(ScalarResource("cpus", 2, "marathon"))
        .addResources(ScalarResource("mem", 256, "marathon"))
        .addResources(ScalarResource("disk", 2000, "marathon"))
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, NetworkInfo)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/testApp".toPath,
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )
      )

      assert(task.isDefined)

      val (taskInfo, networkInfo) = task.get
      val ports = taskInfo.getResourcesList
        .find(r => r.getName == Resource.PORTS)
        .map(r => r.getRanges.getRangeList.flatMap(range => range.getBegin to range.getEnd))
        .getOrElse(Seq.empty)
      assert(ports == networkInfo.hostPorts) // linter:ignore:UnlikelyEquality

      // In this case, the first roles are sufficient so we'll use those first.
      for (r <- taskInfo.getResourcesList) {
        assert(ResourceRole.Unreserved == r.getRole)
      }

      // TODO test for resources etc.
    }

    "PortMappingsWithZeroContainerPort" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31000, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer, AppDefinition(
        id = "/testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        networks = Seq(BridgeNetwork()), container = Some(Docker(

          portMappings = Seq(
            PortMapping(containerPort = 0, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
          )
        ))
      )
      )
      assert(task.isDefined)
      val (taskInfo: TaskInfo, _) = task.get
      val hostPort = taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort
      assert(hostPort == 31000)
      val containerPort = taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort
      assert(containerPort == hostPort)
    }

    "PortMappingsWithUserModeAndDefaultPortMapping" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer, AppDefinition(
        id = "/testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        networks = Seq(ContainerNetwork("vnet")), container = Some(Docker(

          portMappings = Seq(
            PortMapping()
          )
        )),
        portDefinitions = Seq.empty
      )
      )
      assert(task.isDefined, "expected task to match offer")
      val (taskInfo: MesosProtos.TaskInfo, _) = task.get
      assert(taskInfo.getContainer.getDocker.getPortMappingsList.size == 0)

      val envVariables = taskInfo.getCommand.getEnvironment.getVariablesList
      assert(envVariables.exists(v => v.getName == "PORT"))
      assert(envVariables.exists(v => v.getName == "PORT0"))
      assert(envVariables.exists(v => v.getName == "PORTS"))
      assert(envVariables.count(v => v.getName.startsWith("PORT_")) == 1)
    }

    "Docker Container PortMappingsWithoutHostPort" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, AppDefinition(
        id = "/testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        networks = Seq(ContainerNetwork("whatever")), container = Some(Docker(

          portMappings = Seq(
            PortMapping(containerPort = 0, hostPort = Some(31000), servicePort = 9000, protocol = "tcp"),
            PortMapping(containerPort = 0, hostPort = None, servicePort = 9001, protocol = "tcp"),
            PortMapping(containerPort = 0, hostPort = Some(31005), servicePort = 9002, protocol = "tcp")
          )
        ))
      ))
      assert(task.isDefined, "expected task to match offer")
      val (taskInfo: TaskInfo, _) = task.get
      assert(taskInfo.getContainer.getDocker.getPortMappingsList.size == 2, "expected 2 port mappings (ignoring hostPort == None)")

      var hostPort = taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort
      assert(hostPort == 31000)
      var containerPort = taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort
      assert(containerPort == hostPort)

      hostPort = taskInfo.getContainer.getDocker.getPortMappings(1).getHostPort
      assert(hostPort == 31005)
      containerPort = taskInfo.getContainer.getDocker.getPortMappings(1).getContainerPort
      assert(containerPort == hostPort)
    }

    "Mesos Container PortMappingsWithoutHostPort" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, AppDefinition(
        id = "/testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        networks = Seq(ContainerNetwork("whatever")),
        container = Some(Container.Mesos(
          portMappings = Seq(
            PortMapping(containerPort = 0, hostPort = Some(31000), servicePort = 9000, protocol = "tcp"),
            PortMapping(containerPort = 0, hostPort = None, servicePort = 9001, protocol = "tcp"),
            PortMapping(containerPort = 0, hostPort = Some(31005), servicePort = 9002, protocol = "tcp")
          )
        ))
      ))
      assert(task.isDefined, "expected task to match offer")
      val (taskInfo: TaskInfo, _) = task.get
      val ctProto = taskInfo.getContainer
      assert(ctProto.getNetworkInfosCount == 1, "expected a single network info")

      val ctNetworking = ctProto.getNetworkInfos(0)
      assert(ctNetworking.getPortMappingsList.size == 2, "expected 2 port mappings (ignoring hostPort == None)")

      var hostPort = ctNetworking.getPortMappings(0).getHostPort
      assert(hostPort == 31000)
      var containerPort = ctNetworking.getPortMappings(0).getContainerPort
      assert(containerPort == hostPort)

      hostPort = ctNetworking.getPortMappings(1).getHostPort
      assert(hostPort == 31005)
      containerPort = ctNetworking.getPortMappings(1).getContainerPort
      assert(containerPort == hostPort)
    }

    "DockerContainerWithMesosTaskIdLabel" in {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer, AppDefinition(
        id = "testApp".toPath,
        resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
        executor = "//cmd",
        container = Some(Docker(
          image = "busybox"
        ))
      ), None, None, None,
        _ => Task.Id("mesos_task_id")
      )
      assert(task.isDefined, "expected task to match offer")
      val (taskInfo, _) = task.get

      assert(taskInfo.getContainer.getDocker.getParametersList.size == 1, s"expected 1 parameter, but ${taskInfo.getContainer.getDocker.getParametersList.size}")
      val param = taskInfo.getContainer.getDocker.getParametersList.get(0)
      assert(param.getKey == "label", "expected docker having a parameter key: label")
      assert(param.getValue == "MESOS_TASK_ID=mesos_task_id", s"expected docker having a parameter value for key 'label': MESOS_TASK_ID=mesos_task_id but ${param.getValue}")
    }

    "BuildIfMatchesWithRackIdConstraint" in {
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

      val t1 = makeSampleInstance(app.id, "rackid", "2")
      val t2 = makeSampleInstance(app.id, "rackid", "3")
      val s = Seq(t1, t2)

      val config = MarathonTestHelper.defaultConfig()
      val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, s, config.defaultAcceptedResourceRolesSet)
      assert(resourceMatch.isInstanceOf[ResourceMatchResponse.Match])
      // TODO test for resources etc.
    }

    "RackAndHostConstraints" in {
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

      var runningInstances = Set.empty[Instance]
      val config = MarathonTestHelper.defaultConfig()

      val builder = new TaskBuilder(
        app,
        s => Task.Id.forRunSpec(s), config)

      def shouldBuildTask(message: String, offer: Offer): Unit = {
        val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, runningInstances.toIndexedSeq, config.defaultAcceptedResourceRolesSet)
        withClue(message) {
          assert(resourceMatch.isInstanceOf[ResourceMatchResponse.Match])
        }
        val matches = resourceMatch.asInstanceOf[ResourceMatchResponse.Match]
        val (taskInfo, networkInfo) = builder.build(offer, matches.resourceMatch, None)
        val agentInfo = Instance.AgentInfo(
          host = offer.getHostname,
          agentId = Some(offer.getSlaveId.getValue),
          attributes = offer.getAttributesList.toIndexedSeq
        )
        val marathonInstance = TestInstanceBuilder.newBuilder(app.id, version = Timestamp(10))
          .withAgentInfo(agentInfo)
          .addTaskWithBuilder().taskFromTaskInfo(taskInfo, offer).withNetworkInfo(networkInfo).build()
          .getInstance()
        runningInstances += marathonInstance
      }

      def shouldNotBuildTask(message: String, offer: Offer): Unit = {
        val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, runningInstances.toIndexedSeq, config.defaultAcceptedResourceRolesSet)
        assert(resourceMatch.isInstanceOf[ResourceMatchResponse.NoMatch], message)
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

    "UniqueHostNameAndClusterAttribute" in {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        constraints = Set(
          Protos.Constraint.newBuilder.setField("spark").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("enabled").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )

      var runningInstances = Set.empty[Instance]

      val config = MarathonTestHelper.defaultConfig()
      val builder = new TaskBuilder(
        app,
        s => Task.Id.forRunSpec(s), config)

      def shouldBuildTask(offer: Offer): Unit = {
        val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, runningInstances.toIndexedSeq, config.defaultAcceptedResourceRolesSet)
        assert(resourceMatch.isInstanceOf[ResourceMatchResponse.Match])
        val matches = resourceMatch.asInstanceOf[ResourceMatchResponse.Match]
        val (taskInfo, networkInfo) = builder.build(offer, matches.resourceMatch, None)
        val agentInfo = Instance.AgentInfo(
          host = offer.getHostname,
          agentId = Some(offer.getSlaveId.getValue),
          attributes = offer.getAttributesList.toIndexedSeq
        )
        val marathonInstance = TestInstanceBuilder.newBuilder(app.id, version = Timestamp(10))
          .addTaskWithBuilder().taskFromTaskInfo(taskInfo, offer).withNetworkInfo(networkInfo)
          .build()
          .withAgentInfo(agentInfo)
          .getInstance()
        runningInstances += marathonInstance
      }

      def shouldNotBuildTask(message: String, offer: Offer): Unit = {
        val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, runningInstances.toIndexedSeq, config.defaultAcceptedResourceRolesSet)
        assert(resourceMatch.isInstanceOf[ResourceMatchResponse.NoMatch], message)
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
      shouldBuildTask(offerHostB)
    }

    "TaskContextEnv empty when no taskId given" in {
      val version = VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
      val runSpec = AppDefinition(
        id = PathId("/app"),
        versionInfo = version
      )
      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, taskId = None)

      assert(env == Map.empty[String, String])
    }

    "TaskContextEnv minimal" in {
      val version = VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
      val runSpec = AppDefinition(
        id = PathId("/app"),
        versionInfo = version
      )
      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, taskId = Some(Task.Id("taskId")))

      assert(
        env == Map(
          "MESOS_TASK_ID" -> "taskId",
          "MARATHON_APP_ID" -> "/app",
          "MARATHON_APP_VERSION" -> "2015-02-03T12:30:00.000Z",
          "MARATHON_APP_RESOURCE_CPUS" -> App.DefaultCpus.toString,
          "MARATHON_APP_RESOURCE_MEM" -> App.DefaultMem.toString,
          "MARATHON_APP_RESOURCE_DISK" -> App.DefaultDisk.toString,
          "MARATHON_APP_RESOURCE_GPUS" -> App.DefaultGpus.toString,
          "MARATHON_APP_LABELS" -> ""
        )
      )
    }

    "TaskContextEnv all fields" in {
      val version = VersionInfo.forNewConfig(Timestamp(new DateTime(2015, 2, 3, 12, 30, DateTimeZone.UTC)))
      val runSpecId = PathId("/app")
      val runSpec = AppDefinition(
        id = runSpecId,
        versionInfo = version,
        container = Some(Docker(
          image = "myregistry/myimage:version"
        )),
        resources = Resources(cpus = 10.0, mem = 256.0, disk = 128.0, gpus = 2),
        labels = Map(
          "LABEL1" -> "VALUE1",
          "LABEL2" -> "VALUE2"
        )
      )
      val taskId = Task.Id.forRunSpec(runSpecId)
      val env = TaskBuilder.taskContextEnv(runSpec = runSpec, Some(taskId))

      assert(
        env == Map(
          "MESOS_TASK_ID" -> taskId.idString,
          "MARATHON_APP_ID" -> "/app",
          "MARATHON_APP_VERSION" -> "2015-02-03T12:30:00.000Z",
          "MARATHON_APP_DOCKER_IMAGE" -> "myregistry/myimage:version",
          "MARATHON_APP_RESOURCE_CPUS" -> "10.0",
          "MARATHON_APP_RESOURCE_MEM" -> "256.0",
          "MARATHON_APP_RESOURCE_DISK" -> "128.0",
          "MARATHON_APP_RESOURCE_GPUS" -> "2",
          "MARATHON_APP_LABELS" -> "LABEL1 LABEL2",
          "MARATHON_APP_LABEL_LABEL1" -> "VALUE1",
          "MARATHON_APP_LABEL_LABEL2" -> "VALUE2"
        )
      )
    }

    "TaskContextEnv will provide label env safety" in {

      // will exceed max length for sure
      val longLabel = "longlabel" * EnvironmentHelper.maxVariableLength
      val longValue = "longvalue" * EnvironmentHelper.maxEnvironmentVarLength

      val runSpec = AppDefinition(
        id = PathId("/test"),
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

      assert(
        env == Map(
          "MARATHON_APP_LABELS" -> "OTHER_LABEL_A LABEL LABEL_WITH_INVALID_CHARS",
          "MARATHON_APP_LABEL_LABEL" -> "VALUE1",
          "MARATHON_APP_LABEL_LABEL_WITH_INVALID_CHARS" -> "VALUE2",
          "MARATHON_APP_LABEL_OTHER_LABEL_A" -> "VALUE3"
        )
      )
    }

    "AppContextEnvironment" in {
      val command =
        TaskBuilder.commandInfo(
          runSpec = AppDefinition(
            id = "/test".toPath,
            portDefinitions = PortDefinitions(8080, 8081),
            container = Some(Docker(
              image = "myregistry/myimage:version"
            )),
            versionInfo = VersionInfo.OnlyVersion(Timestamp.zero)
          ),
          taskId = Some(Task.Id("task-123")),
          host = Some("host.mega.corp"),
          hostPorts = Helpers.hostPorts(1000, 1001),
          envPrefix = None
        )
      val env: Map[String, String] =
        command.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      assert("task-123" == env("MESOS_TASK_ID"))
      assert("/test" == env("MARATHON_APP_ID"))
      assert("1970-01-01T00:00:00.000Z" == env("MARATHON_APP_VERSION"))
      assert("myregistry/myimage:version" == env("MARATHON_APP_DOCKER_IMAGE"))
    }

    "user defined variables override automatic port variables" in {
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
        command.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      assert("1" == env("PORT"))
      assert("ports" == env("PORTS"))
      assert("1" == env("PORT0"))
      assert("2" == env("PORT1"))
      assert("port8080" == env("PORT_8080"))
      assert("port8081" == env("PORT_8081"))
    }

    "PortsEnvWithOnlyPorts" in {
      val command =
        TaskBuilder.commandInfo(
          runSpec = AppDefinition(
            id = runSpecId,
            portDefinitions = PortDefinitions(8080, 8081)
          ),
          taskId = Some(Task.Id("task-123")),
          host = Some("host.mega.corp"),
          hostPorts = Helpers.hostPorts(1000, 1001),
          envPrefix = None
        )
      val env: Map[String, String] =
        command.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      assert("1000" == env("PORT_8080"))
      assert("1001" == env("PORT_8081"))
    }

    "PortsEnvWithCustomPrefix" in {
      val command =
        TaskBuilder.commandInfo(
          AppDefinition(
            id = runSpecId,
            portDefinitions = PortDefinitions(8080, 8081)
          ),
          Some(Task.Id("task-123")),
          Some("host.mega.corp"),
          Helpers.hostPorts(1000, 1001),
          Some("CUSTOM_PREFIX_")
        )
      val env: Map[String, String] =
        command.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

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

    "OnlyWhitelistedUnprefixedVariablesWithCustomPrefix" in {
      val command =
        TaskBuilder.commandInfo(
          AppDefinition(
            id = runSpecId,
            portDefinitions = PortDefinitions(8080, 8081)
          ),
          Some(Task.Id("task-123")),
          Some("host.mega.corp"),
          Helpers.hostPorts(1000, 1001),
          Some("P_")
        )
      val env: Map[String, String] =
        command.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      val nonPrefixedEnvVars = env.filterKeys(!_.startsWith("P_"))

      val whiteList = Seq("MESOS_TASK_ID", "MARATHON_APP_ID", "MARATHON_APP_VERSION", "MARATHON_APP_RESOURCE_CPUS",
        "MARATHON_APP_RESOURCE_MEM", "MARATHON_APP_RESOURCE_DISK", "MARATHON_APP_RESOURCE_GPUS", "MARATHON_APP_LABELS")

      assert(nonPrefixedEnvVars.keySet.forall(whiteList.contains))
    }

    "PortsEnvWithOnlyMappings" in {
      val command =
        TaskBuilder.commandInfo(
          runSpec = AppDefinition(
            id = runSpecId,
            networks = Seq(BridgeNetwork()), container = Some(Docker(

              portMappings = Seq(
                PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 9000, protocol = "tcp", name = Some("http")),
                PortMapping(containerPort = 8081, hostPort = Some(0), servicePort = 9000, protocol = "tcp", name = Some("jabber"))
              )
            ))
          ),
          taskId = Some(Task.Id("task-123")),
          host = Some("host.mega.corp"),
          hostPorts = Helpers.hostPorts(1000, 1001),
          envPrefix = None
        )
      val env: Map[String, String] =
        command.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      assert("1000" == env("PORT_8080"))
      assert("1001" == env("PORT_8081"))
      assert("1000" == env("PORT_HTTP"))
      assert("1001" == env("PORT_JABBER"))
    }

    "PortsEnvWithBothPortsAndMappings" in {
      a[IllegalArgumentException] shouldBe thrownBy {
        TaskBuilder.commandInfo(
          runSpec = AppDefinition(
            id = runSpecId,
            portDefinitions = PortDefinitions(22, 23),
            networks = Seq(BridgeNetwork()), container = Some(Docker(

              portMappings = Seq(
                PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 9000, protocol = "tcp"),
                PortMapping(containerPort = 8081, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
              )
            ))
          ),
          taskId = Some(Task.Id("task-123")),
          host = Some("host.mega.corp"),
          hostPorts = Helpers.hostPorts(1000, 1001),
          envPrefix = None
        )
      }
    }

    "TaskWillCopyFetchIntoCommand" in {
      val command = TaskBuilder.commandInfo(
        runSpec = AppDefinition(
          id = runSpecId,
          fetch = Seq(
            FetchUri(uri = "http://www.example.com", extract = false, cache = true, executable = false),
            FetchUri(uri = "http://www.example2.com", extract = true, cache = true, executable = true)
          )
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        hostPorts = Helpers.hostPorts(1000, 1001),
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
    "build with requirePorts preserves the port order" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26000).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          portDefinitions = PortDefinitions(25552, 25551),
          requirePorts = true
        )
      )

      val Some((taskInfo: MesosProtos.TaskInfo, _)) = task

      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      assert("25552" == env("PORT0"))
      assert("25552" == env("PORT_25552"))
      assert("25551" == env("PORT1"))
      assert("25551" == env("PORT_25551"))

      val portsFromTaskInfo = {
        val asScalaRanges = for {
          resource <- taskInfo.getResourcesList if resource.getName == Resource.PORTS
          range <- resource.getRanges.getRangeList
        } yield range.getBegin to range.getEnd
        asScalaRanges.flatMap(_.iterator).toList
      }
      assert(portsFromTaskInfo == Seq(25552, 25551)) // linter:ignore:UnlikelyEquality
    }

    "build with virtual networking and optional hostports preserves the port order" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26003).build

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          networks = Seq(ContainerNetwork("vnet")), container = Some(Docker(
            image = "jdef/foo",

            portMappings = Seq(
              // order is important here since it impacts the specific assertions that follow
              Container.PortMapping(containerPort = 0, hostPort = None),
              Container.PortMapping(containerPort = 100, hostPort = Some(0)),
              Container.PortMapping(containerPort = 200, hostPort = Some(25002)),
              Container.PortMapping(containerPort = 0, hostPort = Some(25001)),
              Container.PortMapping(containerPort = 400, hostPort = None),
              Container.PortMapping(containerPort = 0, hostPort = Some(0))
            )
          )),

          portDefinitions = Nil
        )
      )

      val Some((taskInfo: MesosProtos.TaskInfo, _)) = task

      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.toList.map(v => v.getName -> v.getValue).toMap

      // port0 is not allocated from the offer since it's container-only, but it should also not
      // overlap with other (fixed or dynamic) container ports
      assert(env.contains("PORT0"))
      val p0 = env("PORT0")
      assert("0" != env("PORT0"))
      assert("25003" != env("PORT0"))
      assert("25002" != env("PORT0"))
      assert("25001" != env("PORT0"))
      assert("25000" != env("PORT0"))
      assert("100" != env("PORT0"))
      assert("200" != env("PORT0"))
      assert("400" != env("PORT0"))
      assert(p0 == env("PORT_" + p0))
      //? how to test there's never any overlap?

      // port1 picks up a dynamic host port allocated from the offer
      assert(env.contains("PORT1"))
      assert("25002" != env("PORT1"))
      assert("25001" != env("PORT1"))
      assert("0" != env("PORT1"))
      assert(env("PORT1") == env("PORT_100"))

      // port2 picks up a fixed host port allocated from the offer
      assert("25002" == env("PORT2"))
      assert("25002" == env("PORT_200"))

      // port3 picks up a fixed host port allocated from the offer
      assert("25001" == env("PORT3"))
      assert("25001" == env("PORT_25001"))

      // port4 is not allocated from the offer, but it does specify a fixed container port
      assert("400" == env("PORT4"))
      assert("400" == env("PORT_400"))

      // port5 is dynamic, allocated from offer, and should be inherited by the container port
      assert(env.contains("PORT5"))
      val p5 = env("PORT5")
      assert(p5 == env("PORT_" + p5))

      val portsFromTaskInfo = {
        val asScalaRanges = for {
          resource <- taskInfo.getResourcesList if resource.getName == Resource.PORTS
          range <- resource.getRanges.getRangeList
        } yield range.getBegin to range.getEnd
        asScalaRanges.flatMap(_.iterator).toList
      }
      assert(4 == portsFromTaskInfo.size)
      assert(portsFromTaskInfo.contains(25002))
      assert(portsFromTaskInfo.contains(25001))
      assert(portsFromTaskInfo.exists(_.toString == env("PORT1")))
      assert(portsFromTaskInfo.exists(_.toString == env("PORT5")))
    }

    "taskKillGracePeriod specified in app definition is passed through to TaskInfo" in {
      val seconds = 12345.seconds
      val app = MarathonTestHelper.makeBasicApp().copy(
        taskKillGracePeriod = Some(seconds)
      )

      val offer = MarathonTestHelper.makeBasicOffer(1.0, 128.0, 31000, 32000).build
      val config = MarathonTestHelper.defaultConfig()
      val builder = new TaskBuilder(app, s => Task.Id(s.toString), config)
      val runningInstances = Set.empty[Instance]

      val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, runningInstances.toIndexedSeq, config.defaultAcceptedResourceRolesSet)
      assert(resourceMatch.isInstanceOf[ResourceMatchResponse.Match])
      val matches = resourceMatch.asInstanceOf[ResourceMatchResponse.Match]
      val (taskInfo, _) = builder.build(offer, matches.resourceMatch, None)

      assert(taskInfo.hasKillPolicy)
      val killPolicy = taskInfo.getKillPolicy
      assert(killPolicy.hasGracePeriod)
      val gracePeriod = killPolicy.getGracePeriod
      assert(gracePeriod.hasNanoseconds)
      val nanoSeconds = gracePeriod.getNanoseconds
      assert(nanoSeconds == seconds.toNanos)
    }
  }
  def buildIfMatches(
    offer: Offer,
    app: AppDefinition,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None,
    newTaskId: PathId => Task.Id = s => Task.Id.forRunSpec(s)): Option[(MesosProtos.TaskInfo, NetworkInfo)] = {
    val builder = new TaskBuilder(
      app,
      newTaskId,
      MarathonTestHelper.defaultConfig(
        mesosRole = mesosRole,
        acceptedResourceRoles = acceptedResourceRoles,
        envVarsPrefix = envVarsPrefix))

    val config = MarathonTestHelper.defaultConfig()
    val resourceMatch = RunSpecOfferMatcher.matchOffer(app, offer, Seq.empty, acceptedResourceRoles.getOrElse(config.defaultAcceptedResourceRolesSet))

    resourceMatch match {
      case matches: ResourceMatchResponse.Match => Some(builder.build(offer, matches.resourceMatch, None))
      case _ => None
    }
  }

  def makeSampleInstance(appId: PathId, attr: String, attrVal: String) = {
    TestInstanceBuilder.newBuilder(appId).addTaskWithBuilder().taskStaged()
      .withNetworkInfo(hostPorts = Seq(999))
      .build()
      .withAgentInfo(attributes = Some(Seq[MesosProtos.Attribute](TextAttribute(attr, attrVal))))
      .getInstance()
  }

  private def assertTaskInfo(taskInfo: MesosProtos.TaskInfo, hostPorts: Seq[Int], offer: Offer): Unit = {
    val portsFromTaskInfo = {
      val asScalaRanges = for {
        resource <- taskInfo.getResourcesList if resource.getName == Resource.PORTS
        range <- resource.getRanges.getRangeList
      } yield range.getBegin to range.getEnd
      asScalaRanges.flatMap(_.iterator).toSet
    }
    assert(portsFromTaskInfo == hostPorts.toSet) // linter:ignore:UnlikelyEquality

    // The taskName is the elements of the path, reversed, and joined by dots
    assert("frontend.product" == taskInfo.getName)

    assert(!taskInfo.hasExecutor)
    assert(taskInfo.hasCommand)
    val cmd = taskInfo.getCommand
    assert(cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.isEmpty)
    assert(cmd.getValue == "foo")

    assert(cmd.hasEnvironment)
    val envVars = cmd.getEnvironment.getVariablesList
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

    for (r <- taskInfo.getResourcesList) {
      assert(ResourceRole.Unreserved == r.getRole)
    }

    assert(taskInfo.hasDiscovery)
    val discoveryInfo = taskInfo.getDiscovery
    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(Helpers.mesosPorts(
        Helpers.mesosPort("", "tcp", Map.empty, hostPorts.head),
        Helpers.mesosPort("", "tcp", Map.empty, hostPorts(1))
      )).build

    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)

    // TODO test for resources etc.
  }

  object Helpers {
    def hostPorts(p: Int*): Seq[Option[Int]] = collection.immutable.Seq(p: _*).map(Some(_))

    def mesosPort(name: String = "", protocol: String = "", labels: Map[String, String] = Map.empty, hostPort: Int): MesosProtos.Port = {
      val b = MesosProtos.Port.newBuilder.setNumber(hostPort)
      if (name != "") b.setName(name)
      if (protocol != "") b.setProtocol(protocol)
      if (labels.nonEmpty) {
        b.setLabels(labels.toMesosLabels)
      }
      b.build
    }

    def mesosPorts(ports: MesosProtos.Port*) =
      ports.fold(MesosProtos.Ports.newBuilder){
        case (builder: MesosProtos.Ports.Builder, p: MesosProtos.Port) =>
          builder.addPorts(p)
      }.asInstanceOf[MesosProtos.Ports.Builder]
  }
}
