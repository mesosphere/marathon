package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.mesos.protos._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderWithArgsTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given an offer and an app definition with args" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          args = Some(Seq("a", "b", "c")),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val rangeResourceOpt = taskInfo.getResourcesList.asScala.find(r => r.getName == Resource.PORTS)
      val ranges = rangeResourceOpt.fold(Seq.empty[MesosProtos.Value.Range])(_.getRanges.getRangeList.asScala.to[Seq])
      val rangePorts = ranges.flatMap(r => r.getBegin to r.getEnd).toSet
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      "return a defined task" in { task should be('defined) }

      "define a proper ports range" in { rangePorts.size should be(2) }
      "define task ports" in { taskPorts.size should be(2) }
      "define the same range and task ports" in { assert(taskPorts.flatten.toSet == rangePorts.toSet) }

      "not set an executor" in { taskInfo.hasExecutor should be(false) }
      "set a proper command with arguments" in {
        taskInfo.hasCommand should be(true)
        val cmd = taskInfo.getCommand
        assert(!cmd.getShell)
        assert(cmd.hasValue)
        assert(cmd.getArgumentsList.asScala == Seq("a", "b", "c"))
      }

      "set the correct resource roles" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          assert(ResourceRole.Unreserved == r.getRole)
        }
      }

      "set an appropiate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropiate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropiate disk share" in { resource("disk") should be(ScalarResource("disk", 1)) }
    }

    "given an offer and an app definition with a command executor" should {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
        .addResources(ScalarResource("cpus", 1))
        .addResources(ScalarResource("mem", 128))
        .addResources(ScalarResource("disk", 2000))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          cmd = Some("foo"),
          executor = "/custom/executor",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo: MesosProtos.TaskInfo, _) = task.get

      "return a defined task" in { task should be('defined) }

      "not set a command" in { taskInfo.hasCommand should be(false) }
      "set an executor" in { taskInfo.hasExecutor should be(true) }

      val cmd = taskInfo.getExecutor.getCommand
      "set the executor command shell" in { cmd.getShell should be(true) }
      "set the executor command value" in { cmd.getValue should be("chmod ug+rx '/custom/executor' && exec '/custom/executor' foo") }
      "not set an argument list" in { cmd.getArgumentsList.asScala should be('empty) }
    }

    "given an offer and an app definition with arguments and an executor" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          args = Some(Seq("a", "b", "c")),
          executor = "/custom/executor",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo: MesosProtos.TaskInfo, _) = task.get
      val cmd = taskInfo.getExecutor.getCommand

      "return a defined task" in { task should be('defined) }

      "no set a command" in { taskInfo.hasCommand should be(false) }
      "set an executor command" in { cmd.getValue should be("chmod ug+rx '/custom/executor' && exec '/custom/executor' a b c") }
    }

    "given a command with fetch uris" should {
      val command = TaskBuilder.commandInfo(
        runSpec = AppDefinition(
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

      "set the command uris" in {
        command.getUris(0).getValue should be ("http://www.example.com")
        command.getUris(1).getValue should be ("http://www.example2.com")
      }
      "set the command uris cache" in {
        command.getUris(0).getCache should be(true)
        command.getUris(1).getCache should be(true)
      }
      "set one to be non-extractable" in { command.getUris(0).getExtract should be(false) }
      "set one to be extractable" in { command.getUris(1).getExtract should be(true) }
      "set one to be non-executable" in { command.getUris(0).getExecutable should be(false) }
      "set one to be executable" in { command.getUris(1).getExecutable should be(true) }
    }
  }
}
