package mesosphere.mesos

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
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
  }
}
