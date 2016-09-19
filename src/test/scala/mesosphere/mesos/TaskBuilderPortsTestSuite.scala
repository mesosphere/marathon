package mesosphere.mesos

import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.api.serialization.PortDefinitionSerializer
import mesosphere.marathon.state.{ AppDefinition, _ }
import mesosphere.marathon.MarathonTestHelper
import mesosphere.mesos.protos.{ Resource, _ }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderPortsTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given a basic offer and an app definition" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get

      "return a defined task" in { task should be('defined) }
      "return a task without labels" in { taskInfo.hasLabels should be(false) }

      val portsFromTaskInfo = {
        val asScalaRanges = for {
          resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
          range <- resource.getRanges.getRangeList.asScala
        } yield range.getBegin to range.getEnd
        asScalaRanges.flatMap(_.iterator).toSet
      }
      "set the same ports in task info and task ports" in {
        assert(portsFromTaskInfo == taskPorts.flatten.toSet) // linter:ignore:UnlikelyEquality
      }

      "set the task name" in {
        // The taskName is the elements of the path, reversed, and joined by dots
        taskInfo.getName should be("frontend.product")
      }

      "not set an executor" in { taskInfo.hasExecutor should be(false) }
      "set a command" in { taskInfo.hasCommand should be(true) }

      val cmd = taskInfo.getCommand
      "set the command shell" in { cmd.getShell should be(true) }
      "set the command value" in {
        cmd.hasValue should be(true)
        cmd.getValue should be("foo")
      }
      "not set an argument list" in { cmd.getArgumentsList.asScala should be('empty) }

      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap
      "set env variable HOST" in { env("HOST") should be(offer.getHostname) }
      "set env variable PORT0" in { env.keys should contain("PORT0") }
      "set env variable PORT1" in { env.keys should contain("PORT1") }
      "set env variable PORT_8080" in { env.keys should contain("PORT_8080") }
      "set env variable PORT_8081" in { env.keys should contain("PORT_8081") }
      "expose first port PORT0" in { env("PORT0") should be(env("PORT_8080")) }
      "expose second port PORT1" in { env("PORT1") should be(env("PORT_8081")) }

      "set resource roles to unreserved" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          assert(ResourceRole.Unreserved == r.getRole)
        }
      }

      "set discovert info" in { taskInfo.hasDiscovery should be(true) }
      val discoveryInfo = taskInfo.getDiscovery

      "set discovery info" in { taskInfo.hasDiscovery should be(true) }
      "set discovery info name" in { discoveryInfo.getName should be(taskInfo.getName) }
      "set discovery info visibility" in { discoveryInfo.getVisibility should be(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK) }

      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("")
        discoveryInfo.getPorts.getPorts(1).getName should be("")
      }
      "set correct discovery port protocols" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("tcp")
      }
      "set correct discovery port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1).get)
      }

      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1.0)) }
    }

    "given a basic offer with labels" should {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081),
          labels = Map("foo" -> "bar", "test" -> "test")
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get

      "return a defined task" in { task should be('defined) }

      "return a task with labels" in { taskInfo.hasLabels should be(true) }
      "set the correct task labels" in {
        taskInfo.getLabels().getLabels(0).getKey should be("foo")
        taskInfo.getLabels().getLabels(0).getValue should be("bar")
        taskInfo.getLabels().getLabels(1).getKey should be("test")
        taskInfo.getLabels().getLabels(1).getValue should be("test")
      }

      val portsFromTaskInfo = {
        val asScalaRanges = for {
          resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
          range <- resource.getRanges.getRangeList.asScala
        } yield range.getBegin to range.getEnd
        asScalaRanges.flatMap(_.iterator).toSet
      }
      "set the same ports in task info and task ports" in {
        assert(portsFromTaskInfo == taskPorts.flatten.toSet) // linter:ignore:UnlikelyEquality
      }

      "set the task name" in {
        // The taskName is the elements of the path, reversed, and joined by dots
        taskInfo.getName should be("frontend.product")
      }

      "not set an executor" in { taskInfo.hasExecutor should be(false) }
      "set a command" in { taskInfo.hasCommand should be(true) }

      val cmd = taskInfo.getCommand
      "set the command shell" in { cmd.getShell should be(true) }
      "set the command value" in {
        cmd.hasValue should be(true)
        cmd.getValue should be("foo")
      }
      "not set an argument list" in { cmd.getArgumentsList.asScala should be('empty) }

      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap
      "set env variable HOST" in { env("HOST") should be(offer.getHostname) }
      "set env variable PORT0" in { env.keys should contain("PORT0") }
      "set env variable PORT1" in { env.keys should contain("PORT1") }
      "set env variable PORT_8080" in { env.keys should contain("PORT_8080") }
      "set env variable PORT_8081" in { env.keys should contain("PORT_8081") }
      "expose first port PORT0" in { env("PORT0") should be(env("PORT_8080")) }
      "expose second port PORT1" in { env("PORT1") should be(env("PORT_8081")) }

      "set resource roles to unreserved" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          assert(ResourceRole.Unreserved == r.getRole)
        }
      }

      "set discovert info" in { taskInfo.hasDiscovery should be(true) }
      val discoveryInfo = taskInfo.getDiscovery

      "set discovery info" in { taskInfo.hasDiscovery should be(true) }
      "set discovery info name" in { discoveryInfo.getName should be(taskInfo.getName) }
      "set discovery info visibility" in { discoveryInfo.getVisibility should be(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK) }

      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("")
        discoveryInfo.getPorts.getPorts(1).getName should be("")
      }
      "set correct discovery port protocols" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("tcp")
      }
      "set correct discovery port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1).get)
      }

      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1.0)) }
    }

    "given a basic offer an app definition with empty port definition" should {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = Seq.empty
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val envVariables = taskInfo.getCommand.getEnvironment.getVariablesList.asScala

      "return a defined task" in { task should be('defined) }

      "set no ports" in { taskPorts should be('empty) }
      "set no port env variables" in { assert(!envVariables.exists(v => v.getName.startsWith("PORT"))) }
    }

    "given a basic offer and an app defintion with ports and labels" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
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

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val discoveryInfo = taskInfo.getDiscovery

      "return a defined task" in { task should be('defined) }
      "set the discovery info name" in { discoveryInfo.getName should be("frontend.product") }
      "set discovery info has the correct framework visibility" in {
        discoveryInfo.getVisibility should be (MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      }
      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("http")
        discoveryInfo.getPorts.getPorts(1).getName should be("admin")
      }
      "set correct port protocol" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("tcp")
      }
      "set correct port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1).get)
      }
      "set correct port labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getValue should be("127.0.0.1:8080")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getValue should be("127.0.0.1:8081")
      }

    }

    "given an offer with an empty port range" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 31000).build
      val appDef =
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

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)

      "return an undefined task" in { task should not be ('defined) }
    }

    "given an app definition with port on tcp and udp" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = Seq(
            PortDefinition(8080, "udp,tcp", Some("http"), Map("VIP" -> "127.0.0.1:8080")),
            PortDefinition(8081, "udp", Some("admin"), Map("VIP" -> "127.0.0.1:8081"))
          )
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val discoveryInfo = taskInfo.getDiscovery

      "return a defined task" in { task should be('defined) }
      "set the discovery info name" in { discoveryInfo.getName should be("frontend.product") }
      "set discovery info has the correct framework visibility" in {
        discoveryInfo.getVisibility should be (MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      }
      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("http")
        discoveryInfo.getPorts.getPorts(1).getName should be("http")
        discoveryInfo.getPorts.getPorts(2).getName should be("admin")
      }
      "set correct port protocol" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("udp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(2).getProtocol should be("udp")
      }
      "set correct port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(2).getNumber should be(taskPorts(1).get)
      }
      "set correct port labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getValue should be("127.0.0.1:8080")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getValue should be("127.0.0.1:8080")
        discoveryInfo.getPorts.getPorts(2).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(2).getLabels.getLabels(0).getValue should be("127.0.0.1:8081")
      }
    }

    "given a basic offer and an app definition with port name, different protocol and labels" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
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

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val discoveryInfo = taskInfo.getDiscovery

      "return a defined task" in { task should be('defined) }
      "set the discovery info name" in { discoveryInfo.getName should be("frontend.product") }
      "set discovery info has the correct framework visibility" in {
        discoveryInfo.getVisibility should be (MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      }
      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("http")
        discoveryInfo.getPorts.getPorts(1).getName should be("admin")
      }
      "set correct port protocol" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("udp")
      }
      "set correct port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1).get)
      }
      "set correct port labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getValue should be("127.0.0.1:8080")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getValue should be("127.0.0.1:8081")
      }
    }

    "given an offer and an app definition with port mapping with name, protocol and labels" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          container = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
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
            ))
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val discoveryInfo = taskInfo.getDiscovery

      "return a defined task" in { task should be('defined) }
      "set the discovery info name" in { discoveryInfo.getName should be("frontend.product") }
      "set discovery info has the correct framework visibility" in {
        discoveryInfo.getVisibility should be (MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      }
      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("http")
        discoveryInfo.getPorts.getPorts(1).getName should be("admin")
      }
      "set correct port protocol" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("udp")
      }
      "set correct port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1).get)
      }
      "add VIP labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getValue should be("127.0.0.1:8080")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getValue should be("127.0.0.1:8081")
      }
      "add network-scope labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(1).getKey should be("network-scope")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(1).getValue should be("host")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(1).getKey should be("network-scope")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(1).getValue should be("host")
      }
    }

    "given an offer and an app definition with port mapping with name, protocol and labels but no host port" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          container = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
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
            ))
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val discoveryInfo = taskInfo.getDiscovery

      "return a defined task" in { task should be('defined) }
      "set the discovery info name" in { discoveryInfo.getName should be("frontend.product") }
      "set discovery info has the correct framework visibility" in {
        discoveryInfo.getVisibility should be (MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      }
      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("http")
        discoveryInfo.getPorts.getPorts(1).getName should be("admin")
      }
      "set correct port protocol" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("udp")
      }
      "set correct port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(8081)
      }
      "add VIP labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(0).getValue should be("127.0.0.1:8080")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getKey should be("VIP")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(0).getValue should be("127.0.0.1:8081")
      }
      "add network-scope labels" in {
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(1).getKey should be("network-scope")
        discoveryInfo.getPorts.getPorts(0).getLabels.getLabels(1).getValue should be("host")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(1).getKey should be("network-scope")
        discoveryInfo.getPorts.getPorts(1).getLabels.getLabels(1).getValue should be("container")
      }
    }

    "given an offer with duplicated resources and an app definition" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
        .addResources(ScalarResource("cpus", 1))
        .addResources(ScalarResource("mem", 128))
        .addResources(ScalarResource("disk", 2000))
        .build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get

      "return a defined task" in { task should be('defined) }
      "set no task labels" in { taskInfo.hasLabels should be(false) }

      val portsFromTaskInfo = {
        val asScalaRanges = for {
          resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
          range <- resource.getRanges.getRangeList.asScala
        } yield range.getBegin to range.getEnd
        asScalaRanges.flatMap(_.iterator).toSet
      }
      "set the same ports in task info and task ports" in {
        assert(portsFromTaskInfo == taskPorts.flatten.toSet) // linter:ignore:UnlikelyEquality
      }

      "set the task name" in {
        // The taskName is the elements of the path, reversed, and joined by dots
        taskInfo.getName should be("frontend.product")
      }

      "not set an executor" in { taskInfo.hasExecutor should be(false) }
      "set a command" in { taskInfo.hasCommand should be(true) }

      val cmd = taskInfo.getCommand
      "set the command shell" in { cmd.getShell should be(true) }
      "set the command value" in {
        cmd.hasValue should be(true)
        cmd.getValue should be("foo")
      }
      "not set an argument list" in { cmd.getArgumentsList.asScala should be('empty) }

      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap
      "set env variable HOST" in { env("HOST") should be(offer.getHostname) }
      "set env variable PORT0" in { env.keys should contain("PORT0") }
      "set env variable PORT1" in { env.keys should contain("PORT1") }
      "set env variable PORT_8080" in { env.keys should contain("PORT_8080") }
      "set env variable PORT_8081" in { env.keys should contain("PORT_8081") }
      "expose first port PORT0" in { env("PORT0") should be(env("PORT_8080")) }
      "expose second port PORT1" in { env("PORT1") should be(env("PORT_8081")) }

      "set resource roles to unreserved" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          assert(ResourceRole.Unreserved == r.getRole)
        }
      }

      "set discovert info" in { taskInfo.hasDiscovery should be(true) }
      val discoveryInfo = taskInfo.getDiscovery

      "set discovery info" in { taskInfo.hasDiscovery should be(true) }
      "set discovery info name" in { discoveryInfo.getName should be(taskInfo.getName) }
      "set discovery info visibility" in { discoveryInfo.getVisibility should be(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK) }

      "set the correct port names" in {
        discoveryInfo.getPorts.getPorts(0).getName should be("")
        discoveryInfo.getPorts.getPorts(1).getName should be("")
      }
      "set correct discovery port protocols" in {
        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
        discoveryInfo.getPorts.getPorts(1).getProtocol should be("tcp")
      }
      "set correct discovery port numbers" in {
        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0).get)
        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1).get)
      }

      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1.0)) }
    }

    "given an offer with enough resources and an app definition" should {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
      val portsResource: Resource = resource("ports")

      "set an appropiate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropiate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropiate disk share" in { resource("disk") should be(ScalarResource("disk", 1)) }
      "???" in {
        assert(portsResource.getRanges.getRangeList.asScala.map(range => range.getEnd - range.getBegin + 1).sum == 2)
      }
      "set unreserved ports resource role" in { portsResource.getRole should be(ResourceRole.Unreserved) }
    }

    // #1583 Do not pass zero disk resource shares to Mesos
    "given an offer and an app definition with zero disk resource" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          disk = 0.0
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      def resourceOpt(name: String) = taskInfo.getResourcesList.asScala.find(_.getName == name)

      "return a task with an empty disk resources" in { assert(resourceOpt("disk").isEmpty) }
    }

    "given an offer and an app definition to share resources" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000, role = "marathon").build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
        offer,
        appDef,
        mesosRole = Some("marathon"),
        acceptedResourceRoles = Some(Set(ResourceRole.Unreserved, "marathon"))
      )
      val Some((taskInfo, _)) = task
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get
      val portsResource: Resource = resource("ports")

      "set an appropiate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1, "marathon")) }
      "set an appropiate mem share" in { resource("mem") should be(ScalarResource("mem", 64, "marathon")) }
      "set an appropiate disk share" in { resource("disk") should be(ScalarResource("disk", 1, "marathon")) }
      "???" in {
        assert(portsResource.getRanges.getRangeList.asScala.map(range => range.getEnd - range.getBegin + 1).sum == 2)
      }
      "preserve the resource role" in { portsResource.getRole should be("marathon") }
    }

    "given a port defintion with tcp and udp protocol" should {

      val portDefinition = PortDefinition(port = 80, protocol = "tcp,udp")

      val mesosPortDefinition = PortDefinitionSerializer.toMesosProto(portDefinition)

      "return a serialized Mesos proto port definition with the correct size" in { mesosPortDefinition.size should be(2) }
      "return a serialized Mesos proto port definition with the correct protocols" in {
        mesosPortDefinition(0).getProtocol should be("tcp")
        mesosPortDefinition(1).getProtocol should be("udp")
      }
      "return a serialized Mesos proto port definition with the correct port numbers" in {
        mesosPortDefinition(0).getNumber should be(80)
        mesosPortDefinition(1).getNumber should be(80)
      }
    }

    "given a task command with ports and ports mappings" should {

      val portDefinition = PortDefinition(port = 80, protocol = "tcp,udp")

      val mesosPortDefinition = PortDefinitionSerializer.toProto(portDefinition)

      "return a serialized Mesos proto port definition with the correct protocols" in {
        mesosPortDefinition.getProtocol should be("tcp,udp")
      }
      "return a serialized Mesos proto port definition with the correct port number" in {
        mesosPortDefinition.getNumber should be(80)
      }
    }

    "given an offer and an app definition with port mapping and host port" should {

      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          container = Some(Docker(
            network = Some(DockerInfo.Network.USER),
            portMappings = Some(Seq(
              PortMapping(containerPort = 0, hostPort = Some(31000), servicePort = 9000, protocol = "tcp"),
              PortMapping(containerPort = 0, hostPort = None, servicePort = 9001, protocol = "tcp"),
              PortMapping(containerPort = 0, hostPort = Some(31005), servicePort = 9002, protocol = "tcp")
            ))
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo, _) = task.get

      "return a defined task" in { task should be('defined) }

      "define two port mappings" in { taskInfo.getContainer.getDocker.getPortMappingsList.size should be(2) }
      "define the correct host ports" in {
        taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort should be(31000)
        taskInfo.getContainer.getDocker.getPortMappings(1).getHostPort should be(31005)
      }
      "define the correct container ports" in {
        taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort should be(31000)
        taskInfo.getContainer.getDocker.getPortMappings(1).getContainerPort should be(31005)
      }
    }

    "given an offer and an app definition with zero container port" should {

      val offer = MarathonTestHelper.makeBasicOfferWithRole(
        cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31000, role = ResourceRole.Unreserved
      )
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          container = Some(Docker(
            network = Some(DockerInfo.Network.BRIDGE),
            portMappings = Some(Seq(
              PortMapping(containerPort = 0, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
            ))
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo, _) = task.get

      "return a defined task" in { task should be('defined) }

      "set the same container and host port" in {
        taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort should be(31000)
        taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort should be(31000)
      }
    }

    // #2865 Multiple explicit ports are mixed up in task json
    "given an offer and an app definition with multiple explicit ports mixed in" should {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          portDefinitions = PortDefinitions(25552, 25551),
          requirePorts = true
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      val env: Map[String, String] =
        taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

      "set env variable PORT0" in { env("PORT0") should be("25552") }
      "set env variable PORT1" in { env("PORT1") should be("25551") }
      "set env variable PORT_25551" in { env("PORT_25551") should be("25551") }
      "set env variable PORT_25552" in { env("PORT_25552") should be("25552") }

      "set ports in resources" in {
        val portsFromTaskInfo = {
          val asScalaRanges = for {
            resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
            range <- resource.getRanges.getRangeList.asScala
          } yield range.getBegin to range.getEnd
          asScalaRanges.flatMap(_.iterator).toList
        }
        portsFromTaskInfo should be(Seq(25552, 25551))
      }
    }
  }
}
