package mesosphere.mesos

import com.google.common.collect.Lists
import com.google.protobuf.TextFormat
import mesosphere.marathon.{ MarathonSpec, Protos }
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ MarathonTasks, TaskTracker }
import mesosphere.mesos.protos.{ Resource, TaskID, _ }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => MesosProtos }
import org.joda.time.{ DateTime, DateTimeZone }
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.Matchers

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderTest extends MarathonSpec with Matchers {

  import mesosphere.mesos.protos.Implicits._

  test("BuildIfMatches") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    assertTaskInfo(taskInfo, taskPorts, offer)

    assert(!taskInfo.hasLabels)
  }

  test("BuildIfMatches works with duplicated resources") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    assertTaskInfo(taskInfo, taskPorts, offer)

    assert(!taskInfo.hasLabels)
  }

  test("build creates task with appropriate resource share") {
    val offer = makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    val Some((taskInfo, _)) = task

    def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

    assert(resource("cpus") == ScalarResource("cpus", 1))
    assert(resource("mem") == ScalarResource("mem", 64))
    assert(resource("disk") == ScalarResource("disk", 1))
    val portsResource: Resource = resource("ports")
    assert(portsResource.getRanges.getRangeList.asScala.map(range => range.getEnd - range.getBegin + 1).sum == 2)
    assert(portsResource.getRole == "*")
  }

  // #1583 Do not pass zero disk resource shares to Mesos
  test("build does set disk resource to zero in TaskInfo") {
    val offer = makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
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
    val offer = makeBasicOffer(
      cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000, role = "marathon"
    ).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      ),
      mesosRole = Some("marathon"),
      acceptedResourceRoles = Some(Set("*", "marathon"))
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

  test("BuildIfMatchesWithLabels") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val labels = Map("foo" -> "bar", "test" -> "test")

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "/product/frontend".toPath,
        cmd = Some("foo"),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        ports = Seq(8080, 8081),
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
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
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
    assert(taskPorts.head == range.get.getBegin.toInt)
    assert(taskPorts(1) == range.get.getEnd.toInt)

    assert(!taskInfo.hasExecutor)
    assert(taskInfo.hasCommand)
    val cmd = taskInfo.getCommand
    assert(!cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.asScala == Seq("a", "b", "c"))

    for (r <- taskInfo.getResourcesList.asScala) {
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithIpAddress") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        ports = Nil,
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
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        args = Some(Seq("a", "b", "c")),
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "/custom/executor",
        ports = Nil,
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
  }

  test("BuildIfMatchesWithCommandAndExecutor") {
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .addResources(ScalarResource("disk", 2000))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        cmd = Some("foo"),
        executor = "/custom/executor",
        ports = Seq(8080, 8081)
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
    val offer = makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        args = Some(Seq("a", "b", "c")),
        executor = "/custom/executor",
        ports = Seq(8080, 8081)
      )
    )

    assert(task.isDefined)

    val (taskInfo, _) = task.get
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

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 2.0,
        mem = 200.0,
        disk = 2.0,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      ),
      mesosRole = Some("marathon")
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
    val offer = makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "*")
      .addResources(ScalarResource("cpus", 1, "*"))
      .addResources(ScalarResource("mem", 128, "*"))
      .addResources(ScalarResource("disk", 1000, "*"))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(ScalarResource("disk", 2000, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp".toPath,
        cpus = 1.0,
        mem = 64.0,
        disk = 1.0,
        executor = "//cmd",
        ports = Seq(8080, 8081)
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
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("PortMappingsWithZeroContainerPort") {
    val offer = makeBasicOfferWithRole(
      cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31000, role = "*"
    )
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(MesosProtos.TaskInfo, Seq[Long])] = buildIfMatches(
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
    val taskTracker = mock[TaskTracker]

    val offer = makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addAttributes(TextAttribute("rackid", "1"))
      .build

    val app = makeBasicApp().copy(
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

    when(taskTracker.get(app.id)).thenReturn(s)

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), defaultConfig())

    val task = builder.buildIfMatches(offer, taskTracker.get(app.id))

    assert(task.isDefined)
    // TODO test for resources etc.
  }

  test("RackAndHostConstraints") {
    // Test the case where we want tasks to be balanced across racks/AZs
    // and run only one per machine
    val app = makeBasicApp().copy(
      instances = 10,
      constraints = Set(
        Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
        Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
      )
    )

    var runningTasks = Set.empty[Protos.MarathonTask]
    val taskTracker = mock[TaskTracker]
    when(taskTracker.get(app.id)).thenAnswer(new Answer[Set[Protos.MarathonTask]] {
      override def answer(p1: InvocationOnMock): Set[Protos.MarathonTask] = runningTasks
    })

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), defaultConfig())

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer, taskTracker.get(app.id))
      assert(tupleOption.isDefined, message)
      val marathonTask = MarathonTasks.makeTask(
        id = tupleOption.get._1.getTaskId.getValue,
        host = offer.getHostname,
        ports = tupleOption.get._2,
        attributes = offer.getAttributesList.asScala.toList,
        version = Timestamp.now(),
        now = Timestamp.now(),
        slaveId = offer.slaveId)
      runningTasks += marathonTask
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer, taskTracker.get(app.id))
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
        Protos.Constraint.newBuilder.setField("spark").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("enabled").build,
        Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
      )
    )

    var runningTasks = Set.empty[Protos.MarathonTask]
    val taskTracker = mock[TaskTracker]
    when(taskTracker.get(app.id)).thenAnswer(new Answer[Set[Protos.MarathonTask]] {
      override def answer(p1: InvocationOnMock): Set[Protos.MarathonTask] = runningTasks
    })

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString), defaultConfig())

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer, taskTracker.get(app.id))
      assert(tupleOption.isDefined, message)
      val marathonTask = MarathonTasks.makeTask(
        id = tupleOption.get._1.getTaskId.getValue,
        host = offer.getHostname,
        ports = tupleOption.get._2,
        attributes = offer.getAttributesList.asScala.toList,
        version = Timestamp.now(),
        now = Timestamp.now(),
        slaveId = offer.slaveId)
      runningTasks += marathonTask
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer, taskTracker.get(app.id))
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
    val env = TaskBuilder.taskContextEnv(app = app, taskId = Some(TaskID("taskId")))

    assert(
      env == Map(
        "MESOS_TASK_ID" -> "taskId",
        "MARATHON_APP_ID" -> "/app",
        "MARATHON_APP_VERSION" -> "2015-02-03T12:30:00.000Z"
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
      ))
    )
    val env = TaskBuilder.taskContextEnv(app = app, Some(taskId))

    assert(
      env == Map(
        "MESOS_TASK_ID" -> "taskId",
        "MARATHON_APP_ID" -> "/app",
        "MARATHON_APP_VERSION" -> "2015-02-03T12:30:00.000Z",
        "MARATHON_APP_DOCKER_IMAGE" -> "myregistry/myimage:version"
      )
    )
  }

  test("AppContextEnvironment") {
    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          id = "/test".toPath,
          ports = Seq(8080, 8081),
          container = Some(Container(
            docker = Some(Docker(
              image = "myregistry/myimage:version"
            ))
          )
          )
        ),
        taskId = Some(TaskID("task-123")),
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
          ports = Seq(8080, 8081),
          env = Map(
            "PORT" -> "1",
            "PORTS" -> "ports",
            "PORT0" -> "1",
            "PORT1" -> "2",
            "PORT_8080" -> "port8080",
            "PORT_8081" -> "port8081"
          )
        ),
        taskId = Some(TaskID("task-123")),
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
          ports = Seq(8080, 8081)
        ),
        taskId = Some(TaskID("task-123")),
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
          ports = Seq(8080, 8081)
        ),
        Some(TaskID("task-123")),
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
          ports = Seq(8080, 8081)
        ),
        Some(TaskID("task-123")),
        Some("host.mega.corp"),
        Seq(1000, 1001),
        Some("P_")
      )
    val env: Map[String, String] =
      command.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap

    val nonPrefixedEnvVars = env.filterKeys(!_.startsWith("P_"))
    val whiteList = Seq("MESOS_TASK_ID", "MARATHON_APP_ID", "MARATHON_APP_VERSION")

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
        taskId = Some(TaskID("task-123")),
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
          ports = Seq(22, 23),
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
        taskId = Some(TaskID("task-123")),
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

  test("TaskNoURIExtraction") {

    val command =
      TaskBuilder.commandInfo(
        app = AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          uris = Seq("http://www.example.com", "http://www.example.com/test.tgz", "example.tar.gz"),
          ports = Seq(8080, 8081)
        ),
        taskId = Some(TaskID("task-123")),
        host = Some("host.mega.corp"),
        ports = Seq(1000, 1001),
        envPrefix = None
      )

    val uriinfo1 = command.getUris(0)
    assert(!uriinfo1.getExtract)
    val uriinfo2 = command.getUris(1)
    assert(uriinfo2.getExtract)
    val uriinfo3 = command.getUris(2)
    assert(uriinfo3.getExtract)

  }

  def buildIfMatches(
    offer: Offer,
    app: AppDefinition,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None) = {
    val taskTracker = mock[TaskTracker]

    val builder = new TaskBuilder(app,
      s => TaskID(s.toString),
      defaultConfig(
        mesosRole = mesosRole,
        acceptedResourceRoles = acceptedResourceRoles,
        envVarsPrefix = envVarsPrefix))

    builder.buildIfMatches(offer, taskTracker.get(app.id))
  }

  def makeSampleTask(id: PathId, attr: String, attrVal: String) = {
    Protos.MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id.toString)
      .addAttributes(TextAttribute(attr, attrVal))
      .build()
  }

  private def assertTaskInfo(taskInfo: MesosProtos.TaskInfo, taskPorts: Seq[Long], offer: Offer): Unit = {
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
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }
}
