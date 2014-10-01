package mesosphere.mesos

import com.google.common.collect.Lists
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.{ Constraint, MarathonTask }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.tasks.{ MarathonTasks, TaskTracker }
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.{ Offer, TaskInfo }
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._

class TaskBuilderTest extends MarathonSpec {

  import mesosphere.mesos.protos.Implicits._

  test("BuildIfMatches") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cmd = Some("foo"),
        cpus = 1,
        mem = 64,
        disk = 1,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == Resource.PORTS)
      .map(r => r.getRanges.getRange(0))
    assert(range.isDefined)
    assert(2 == taskPorts.size)
    assert(taskPorts(0) == range.get.getBegin.toInt)
    assert(taskPorts(1) == range.get.getEnd.toInt)

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

    for (r <- taskInfo.getResourcesList.asScala) {
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithArgs") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1,
        mem = 64,
        disk = 1,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == Resource.PORTS)
      .map(r => r.getRanges.getRange(0))
    assert(range.isDefined)
    assert(2 == taskPorts.size)
    assert(taskPorts(0) == range.get.getBegin.toInt)
    assert(taskPorts(1) == range.get.getEnd.toInt)

    assert(!taskInfo.hasExecutor)
    assert(taskInfo.hasCommand)
    val cmd = taskInfo.getCommand
    assert(!cmd.getShell)
    assert(!cmd.hasValue)
    assert(cmd.getArgumentsList.asScala == Seq("a", "b", "c"))

    for (r <- taskInfo.getResourcesList.asScala) {
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }
  test("BuildIfMatchesWithCommandAndExecutor") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1,
        mem = 64,
        disk = 1,
        cmd = Some("foo"),
        executor = "/custom/executor",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    assert(taskInfo.hasExecutor)
    assert(!taskInfo.hasCommand)

    val cmd = taskInfo.getExecutor.getCommand
    assert(cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.asScala.isEmpty)
    assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' foo")
  }

  test("BuildIfMatchesWithArgsAndExecutor") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1,
        mem = 64,
        disk = 1,
        args = Some(Seq("a", "b", "c")),
        executor = "/custom/executor",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val cmd = taskInfo.getExecutor.getCommand

    assert(!taskInfo.hasCommand)
    assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' a b c")
  }

  test("BuildIfMatchesWithRole") {
    val offer = makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "marathon")
      .addResources(ScalarResource("cpus", 1, "*"))
      .addResources(ScalarResource("mem", 128, "*"))
      .addResources(ScalarResource("disk", 1000, "*"))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(ScalarResource("disk", 2000, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 2,
        mem = 200,
        disk = 2,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == Resource.PORTS)
      .map(r => r.getRanges.getRange(0))
    assert(range.isDefined)
    assert(2 == taskPorts.size)
    assert(taskPorts(0) == range.get.getBegin.toInt)
    assert(taskPorts(1) == range.get.getEnd.toInt)

    for (r <- taskInfo.getResourcesList.asScala) {
      assert("marathon" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithRole2") {
    val offer = makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "*")
      .addResources(ScalarResource("cpus", 1, "*"))
      .addResources(ScalarResource("mem", 128, "*"))
      .addResources(ScalarResource("disk", 1000, "*"))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(ScalarResource("disk", 2000, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1,
        mem = 64,
        disk = 1,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == Resource.PORTS)
      .map(r => r.getRanges.getRange(0))
    assert(range.isDefined)
    assert(2 == taskPorts.size)
    assert(taskPorts(0) == range.get.getBegin.toInt)
    assert(taskPorts(1) == range.get.getEnd.toInt)

    // In this case, the first roles are sufficient so we'll use those first.
    for (r <- taskInfo.getResourcesList.asScala) {
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithRackIdConstraint") {
    val taskTracker = mock[TaskTracker]

    val offer = makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addAttributes(TextAttribute("rackid", "1"))
      .build

    val app = makeBasicApp().copy(
      constraints = Set(
        Constraint.newBuilder
          .setField("rackid")
          .setOperator(Constraint.Operator.UNIQUE)
          .build()
      )
    )

    val t1 = makeSampleTask(app.id, "rackid", "2")
    val t2 = makeSampleTask(app.id, "rackid", "3")
    val s = Set(t1, t2)

    when(taskTracker.get(app.id)).thenReturn(s)

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), taskTracker, defaultConfig())

    val task = builder.buildIfMatches(offer)

    assert(task.isDefined)
    // TODO test for resources etc.
  }

  test("RackAndHostConstraints") {
    // Test the case where we want tasks to be balanced across racks/AZs
    // and run only one per machine
    val app = makeBasicApp().copy(
      instances = 10,
      constraints = Set(
        Constraint.newBuilder.setField("rackid").setOperator(Constraint.Operator.GROUP_BY).setValue("3").build,
        Constraint.newBuilder.setField("hostname").setOperator(Constraint.Operator.UNIQUE).build
      )
    )

    var runningTasks = Set.empty[MarathonTask]
    val taskTracker = mock[TaskTracker]
    when(taskTracker.get(app.id)).thenAnswer(new Answer[Set[MarathonTask]] {
      override def answer(p1: InvocationOnMock): Set[MarathonTask] = runningTasks
    })

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), taskTracker, defaultConfig())

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assert(tupleOption.isDefined, message)
      val marathonTask = MarathonTasks.makeTask(
        tupleOption.get._1.getTaskId.getValue,
        offer.getHostname,
        tupleOption.get._2,
        offer.getAttributesList.asScala.toList,
        Timestamp.now)
      runningTasks += marathonTask
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assert(tupleOption.isEmpty, message)
    }

    val offerRack1HostA = makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(TextAttribute("rackid", "1"))
      .build
    shouldBuildTask("Should take first offer", offerRack1HostA)

    val offerRack1HostB = makeBasicOffer()
      .setHostname("beta")
      .addAttributes(TextAttribute("rackid", "1"))
      .build
    shouldNotBuildTask("Should not take offer for the same rack", offerRack1HostB)

    val offerRack2HostC = makeBasicOffer()
      .setHostname("gamma")
      .addAttributes(TextAttribute("rackid", "2"))
      .build
    shouldBuildTask("Should take offer for different rack", offerRack2HostC)

    // Nothing prevents having two hosts with the same name in different racks
    val offerRack3HostA = makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(TextAttribute("rackid", "3"))
      .build
    shouldNotBuildTask("Should not take offer in different rack with non-unique hostname", offerRack3HostA)
  }

  test("UniqueHostNameAndClusterAttribute") {
    val app = makeBasicApp().copy(
      instances = 10,
      constraints = Set(
        Constraint.newBuilder.setField("spark").setOperator(Constraint.Operator.CLUSTER).setValue("enabled").build,
        Constraint.newBuilder.setField("hostname").setOperator(Constraint.Operator.UNIQUE).build
      )
    )

    var runningTasks = Set.empty[MarathonTask]
    val taskTracker = mock[TaskTracker]
    when(taskTracker.get(app.id)).thenAnswer(new Answer[Set[MarathonTask]] {
      override def answer(p1: InvocationOnMock): Set[MarathonTask] = runningTasks
    })

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), taskTracker, defaultConfig())

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assert(tupleOption.isDefined, message)
      val marathonTask = MarathonTasks.makeTask(
        tupleOption.get._1.getTaskId.getValue,
        offer.getHostname,
        tupleOption.get._2,
        offer.getAttributesList.asScala.toList, Timestamp.now)
      runningTasks += marathonTask
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assert(tupleOption.isEmpty, message)
    }

    val offerHostA = makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(TextAttribute("spark", "disabled"))
      .build
    shouldNotBuildTask("Should not take an offer with spark:disabled", offerHostA)

    val offerHostB = makeBasicOffer()
      .setHostname("beta")
      .addAttributes(TextAttribute("spark", "enabled"))
      .build
    shouldBuildTask("Should take offer with spark:enabled", offerHostB)
  }

  test("PortsEnv") {
    val env = TaskBuilder.portsEnv(Seq(1001, 1002))
    assert("1001" == env("PORT"))
    assert("1001" == env("PORT0"))
    assert("1002" == env("PORT1"))
  }

  test("PortsEnvEmpty") {
    val env = TaskBuilder.portsEnv(Seq())
    assert(Map.empty == env)
  }

  test("TaskNoURIExtraction") {

    val command =
      TaskBuilder.commandInfo(
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1,
          mem = 64,
          disk = 1,
          executor = "//cmd",
          uris = Seq("http://www.example.com", "http://www.example.com/test.tgz",
            "example.tar.gz"),
          ports = Seq(8080, 8081)
        ),
        Some("host.mega.corp"),
        Seq(1000, 1001)
      )

    val uriinfo1 = command.getUris(0)
    assert(!uriinfo1.getExtract)
    val uriinfo2 = command.getUris(1)
    assert(uriinfo2.getExtract)
    val uriinfo3 = command.getUris(2)
    assert(uriinfo3.getExtract)

  }

  def buildIfMatches(offer: Offer, app: AppDefinition) = {
    val taskTracker = mock[TaskTracker]

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), taskTracker, defaultConfig())

    builder.buildIfMatches(offer)
  }

  def makeSampleTask(id: PathId, attr: String, attrVal: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id.toString)
      .addAttributes(TextAttribute(attr, attrVal))
      .build()
  }
}
