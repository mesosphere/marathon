package mesosphere.mesos

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state._
import mesosphere.mesos.protos._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderIPAddressTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given an offer and an app definition with ip address defined" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          args = Some(Seq("a", "b", "c")),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          portDefinitions = Nil,
          executor = AppDefinition.DefaultExecutor,
          ipAddress = Some(
            IpAddress(
              groups = Seq("a", "b", "c"),
              labels = Map(
                "foo" -> "bar",
                "baz" -> "buzz"
              ),
              discoveryInfo = DiscoveryInfo.empty,
              networkName = None
            )
          )
        )
      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala

      "return a defined task" in { task should be('defined) }

      "not set an executor" in { taskInfo.hasExecutor should be (false) }
      "set a container" in { taskInfo.hasContainer should be (true) }

      "set network info" in { networkInfos.size should be(1) }
      "set network info IP addresses" in { networkInfos.head.getIpAddresses(0) should be(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance) }
      "set network info groups" in {
        networkInfos.head.getGroupsCount should be(3)
        networkInfos.head.getGroups(0) should be("a")
        networkInfos.head.getGroups(1) should be("b")
        networkInfos.head.getGroups(2) should be("c")
      }
      "set network info labels" in {
        networkInfos.head.getLabels.getLabels(0).getKey should be("foo")
        networkInfos.head.getLabels.getLabels(0).getValue should be("bar")
        networkInfos.head.getLabels.getLabels(1).getKey should be("baz")
        networkInfos.head.getLabels.getLabels(1).getValue should be("buzz")
      }
    }

    "given an offer and an app definition with ip address and custom executor defined" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          args = Some(Seq("a", "b", "c")),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          portDefinitions = Nil,
          executor = "/custom/executor",
          ipAddress = Some(
            IpAddress(
              groups = Seq("a", "b", "c"),
              labels = Map(
                "foo" -> "bar",
                "baz" -> "buzz"
              ),
              discoveryInfo = DiscoveryInfo.empty,
              networkName = None
            )
          )
        )
      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val networkInfos = taskInfo.getExecutor.getContainer.getNetworkInfosList.asScala

      "return a defined task" in { task should be('defined) }

      "set an executor" in { taskInfo.hasExecutor should be (true) }
      "set an executor container" in { taskInfo.getExecutor.hasContainer should be(true) }
      "not set a container" in { taskInfo.hasContainer should be (false) }

      "set network info" in { networkInfos.size should be(1) }
      "set network info IP addresses" in { networkInfos.head.getIpAddresses(0) should be(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance) }
      "set network info groups" in {
        networkInfos.head.getGroupsCount should be(3)
        networkInfos.head.getGroups(0) should be("a")
        networkInfos.head.getGroups(1) should be("b")
        networkInfos.head.getGroups(2) should be("c")
      }
      "set network info labels" in {
        networkInfos.head.getLabels.getLabels(0).getKey should be("foo")
        networkInfos.head.getLabels.getLabels(0).getValue should be("bar")
        networkInfos.head.getLabels.getLabels(1).getKey should be("baz")
        networkInfos.head.getLabels.getLabels(1).getValue should be("buzz")
      }

      "set discovery info" in {
        taskInfo.hasDiscovery should be(true)
        taskInfo.getDiscovery.getName should be("testApp")
      }
    }

    "given an offer and an app definition with ip address and network name defined" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          args = Some(Seq("a", "b", "c")),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          portDefinitions = Nil,
          executor = AppDefinition.DefaultExecutor,
          ipAddress = Some(
            IpAddress(
              groups = Seq("a", "b", "c"),
              labels = Map(
                "foo" -> "bar",
                "baz" -> "buzz"
              ),
              discoveryInfo = DiscoveryInfo.empty,
              networkName = Some("foonet")
            )
          )
        )
      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala

      "return a defined task" in { task should be('defined) }

      "not set an executor" in { taskInfo.hasExecutor should be (false) }
      "set a container" in { taskInfo.hasContainer should be (true) }

      "set network info" in { networkInfos.size should be(1) }
      "set network info IP addresses" in { networkInfos.head.getIpAddresses(0) should be(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance) }
      "set network info groups" in {
        networkInfos.head.getGroupsCount should be(3)
        networkInfos.head.getGroups(0) should be("a")
        networkInfos.head.getGroups(1) should be("b")
        networkInfos.head.getGroups(2) should be("c")
      }
      "set network info labels" in {
        networkInfos.head.getLabels.getLabels(0).getKey should be("foo")
        networkInfos.head.getLabels.getLabels(0).getValue should be("bar")
        networkInfos.head.getLabels.getLabels(1).getKey should be("baz")
        networkInfos.head.getLabels.getLabels(1).getValue should be("buzz")
      }
      "set network info name" in {
        networkInfos.head.getName should be("foonet")
      }
    }

    "given an offer and an app definition with ip address and discovery info defined" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val info = DiscoveryInfo(ports = Seq(DiscoveryInfo.Port(name = "http", number = 80, protocol = "tcp")))
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          args = Some(Seq("a", "b", "c")),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          portDefinitions = Nil,
          executor = AppDefinition.DefaultExecutor,
          ipAddress = Some(
            IpAddress(
              groups = Seq("a", "b", "c"),
              labels = Map(
                "foo" -> "bar",
                "baz" -> "buzz"
              ),
              discoveryInfo = info,
              networkName = None
            )
          )
        )
      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala
      val discoveryInfo = taskInfo.getDiscovery

      "return a defined task" in { task should be('defined) }

      "not set an executor" in { taskInfo.hasExecutor should be (false) }
      "set a container" in { taskInfo.hasContainer should be (true) }

      "set network info" in { networkInfos.size should be(1) }
      "set network info IP addresses" in { networkInfos.head.getIpAddresses(0) should be(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance) }
      "set network info groups" in {
        networkInfos.head.getGroupsCount should be(3)
        networkInfos.head.getGroups(0) should be("a")
        networkInfos.head.getGroups(1) should be("b")
        networkInfos.head.getGroups(2) should be("c")
      }
      "set network info labels" in {
        networkInfos.head.getLabels.getLabels(0).getKey should be("foo")
        networkInfos.head.getLabels.getLabels(0).getValue should be("bar")
        networkInfos.head.getLabels.getLabels(1).getKey should be("baz")
        networkInfos.head.getLabels.getLabels(1).getValue should be("buzz")
      }

      "set discovery info" in { taskInfo.hasDiscovery should be(true) }
      "set discovery info name" in { discoveryInfo.getName should be("testApp") }
      "set discovery info visibility" in { discoveryInfo.getVisibility should be(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK) }
      "set the correct port names" in { discoveryInfo.getPorts.getPorts(0).getName should be("http") }
      "set correct port protocol" in { discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp") }
      "set correct port numbers" in { discoveryInfo.getPorts.getPorts(0).getNumber should be(80) }
    }

    "given an offer and an app definition with virtual networking and optional host ports" should {
      //  test("build with virtual networking and optional hostports preserves the port order") {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26003).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          container = Some(Docker(
            image = "jdef/foo",
            network = Some(MesosProtos.ContainerInfo.DockerInfo.Network.USER),
            portMappings = Some(Seq(
              // order is important here since it impacts the specific assertions that follow
              Container.Docker.PortMapping(containerPort = 0, hostPort = None),
              Container.Docker.PortMapping(containerPort = 100, hostPort = Some(0)),
              Container.Docker.PortMapping(containerPort = 200, hostPort = Some(25002)),
              Container.Docker.PortMapping(containerPort = 0, hostPort = Some(25001)),
              Container.Docker.PortMapping(containerPort = 400, hostPort = None),
              Container.Docker.PortMapping(containerPort = 0, hostPort = Some(0))
            ))
          )),
          ipAddress = Some(IpAddress(networkName = Some("vnet"))),
          portDefinitions = Nil
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

      // port0 is not allocated from the offer since it's container-only, but it should also not
      // overlap with other (fixed or dynamic) container ports
      "set env variable PORT0" in { env.keys should contain("PORT0") }
      "not overlap PORT0 with other (fixed or dynamic container ports)" in {
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
      }

      "set env variable PORT1" in { env.keys should contain("PORT1") }
      "set an dynamic host port for PORT1" in {
        // port1 picks up a dynamic host port allocated from the offer
        assert("25002" != env("PORT1"))
        assert("25001" != env("PORT1"))
        assert("0" != env("PORT1"))
        assert(env("PORT1") == env("PORT_100"))
      }

      "set a fixed host port allocated from the offer for PORT2" in {
        // port2 picks up a fixed host port allocated from the offer
        assert("25002" == env("PORT2"))
        assert("25002" == env("PORT_200"))
      }

      "set a fixed host port allocated from the offer for PORT3" in {
        // port3 picks up a fixed host port allocated from the offer
        assert("25001" == env("PORT3"))
        assert("25001" == env("PORT_25001"))
      }

      "set a fixed container port for PORT4" in {
        // port4 is not allocated from the offer, but it does specify a fixed container port
        assert("400" == env("PORT4"))
        assert("400" == env("PORT_400"))
      }

      "set a dynamic port allocated from offer for PORT5" in {
        // port5 is dynamic, allocated from offer, and should be inherited by the container port
        assert(env.contains("PORT5"))
        val p5 = env("PORT5")
        assert(p5 == env("PORT_" + p5))
      }

      val portsFromTaskInfo = {
        val asScalaRanges = for {
          resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
          range <- resource.getRanges.getRangeList.asScala
        } yield range.getBegin to range.getEnd
        asScalaRanges.flatMap(_.iterator).toList
      }
      "set ports in ports task info" in { assert(4 == portsFromTaskInfo.size) }
      "set port 25002 in ports task info" in { assert(portsFromTaskInfo.exists(_ == 25002)) }
      "set port 25001 in ports task info" in { assert(portsFromTaskInfo.exists(_ == 25001)) }
      "set PORT1 in ports task info" in { assert(portsFromTaskInfo.exists(_.toString == env("PORT1"))) }
      "set PORT5 in ports task info" in { assert(portsFromTaskInfo.exists(_.toString == env("PORT5"))) }
    }
  }
}
