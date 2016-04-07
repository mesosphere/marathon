package mesosphere.mesos

import mesosphere.marathon.MarathonTestHelper.Implicits._
import mesosphere.marathon.Protos.{ Constraint }
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ FullVersionInfo, OnlyVersion }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ ResourceRole, Timestamp, AppDefinition, PortDefinitions }
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.protos.{ TextAttribute, Resource }
import org.apache.mesos.Protos.Attribute
import org.scalatest.Matchers

import mesosphere.mesos.protos.Implicits._

class ResourceMatcherTest extends MarathonSpec with Matchers {
  test("match with app.disk == 0, even if no disk resource is contained in the offer") {
    import scala.collection.JavaConverters._
    val offerBuilder = MarathonTestHelper.makeBasicOffer()
    val diskResourceIndex = offerBuilder.getResourcesList.asScala.indexWhere(_.getName == "disk")
    offerBuilder.removeResources(diskResourceIndex)
    val offer = offerBuilder.build()

    offer.getResourcesList.asScala.find(_.getName == "disk") should be('empty)

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.DISK) should be(empty)

    res.hostPorts should have size 2
  }

  test("match resources success") {
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.DISK) should be(empty)

    res.hostPorts should have size 2
  }

  test("match resources success with preserved reservations") {
    // have unique reservation to make sure that the reservations are really preserved
    val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels = Map("some" -> "label"))
    val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels = Map("some" -> "label2"))
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("resource" -> "mem"))
    val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("resource" -> "disk"))
    val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal", labels = Map("resource" -> "ports"))

    val offer =
      MarathonTestHelper.makeBasicOffer(role = "marathon")
        .clearResources()
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
        .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
        .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
        .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
        .build()

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 2.0,
      mem = 128.0,
      disk = 2.0,
      portDefinitions = PortDefinitions(0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector(Set(ResourceRole.Unreserved, "marathon"), reserved = true))

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatches should have size (3)
    res.scalarMatch(Resource.CPUS).get.consumed.toSet should be(
      Set(
        ScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation)),
        ScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation2))
      )
    )

    res.scalarMatch(Resource.MEM).get.consumed.toSet should be(
      Set(
        ScalarMatch.Consumption(128.0, ResourceRole.Unreserved, reservation = Some(memReservation))
      )
    )
    res.scalarMatch(Resource.DISK).get.consumed.toSet should be(
      Set(
        ScalarMatch.Consumption(2, ResourceRole.Unreserved, reservation = Some(diskReservation))
      )
    )

    res.portsMatch.hostPortsWithRole.toSet should be(
      Set(PortsMatcher.PortWithRole(ResourceRole.Unreserved, 80, reservation = Some(portsReservation)))
    )

    // reserved resources should not be matched by selector with reserved = false
    ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector(Set(ResourceRole.Unreserved, "marathon"), reserved = false)) should be(None)
  }

  test("match resources should not consider resources with disk infos") {
    val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels = Map("some" -> "label"))
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("resource" -> "mem"))

    val offer =
      MarathonTestHelper.makeBasicOffer(role = "marathon")
        .clearResources()
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
        .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
        .addResources(MarathonTestHelper.reservedDisk(id = "disk", size = 1024.0))
        .build()

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 2.0,
      portDefinitions = PortDefinitions()
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector(Set(ResourceRole.Unreserved, "marathon"), reserved = true)
    )

    resOpt should be(empty)
  }

  test("match resources success with preserved roles") {
    val offer = MarathonTestHelper.makeBasicOffer(role = "marathon").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector(Set("marathon"), reserved = false))

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq("marathon"))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq("marathon"))
    res.scalarMatch(Resource.DISK) should be(empty)
  }

  test("match resources failure because of incorrect roles") {
    val offer = MarathonTestHelper.makeBasicOffer(role = "marathon").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.wildcard)

    resOpt should be ('empty)
  }

  test("match resources success with constraints") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      constraints = Set(
        Constraint.newBuilder
          .setField("hostname")
          .setOperator(Operator.LIKE)
          .setValue("host1")
          .build()
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should not be empty
  }

  test("match resources fails on constraints") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      constraints = Set(
        Constraint.newBuilder
          .setField("hostname")
          .setOperator(Operator.LIKE)
          .setValue("host2")
          .build()
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should be (empty)
  }

  test("match resources fail on cpu") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should be (empty)
  }

  test("match resources fail on mem") {
    val offer = MarathonTestHelper.makeBasicOffer(mem = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should be (empty)
  }

  test("match resources fail on disk") {
    val offer = MarathonTestHelper.makeBasicOffer(disk = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should be (empty)
  }

  test("match resources fail on ports") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(1, 2)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, ResourceSelector.wildcard)

    resOpt should be (empty)
  }

  test("match resources success with constraints and old tasks in previous version") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0)
      .addAttributes(TextAttribute("region", "pl-east"))
      .addAttributes(TextAttribute("zone", "pl-east-1b"))
      .build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      versionInfo = OnlyVersion(Timestamp(2)),
      constraints = Set(
        Constraint.newBuilder
          .setField("region")
          .setOperator(Operator.GROUP_BY)
          .setValue("2")
          .build(),
        Constraint.newBuilder
          .setField("zone")
          .setOperator(Operator.GROUP_BY)
          .setValue("4")
          .build()
      )
    )
    val oldVersion = Timestamp(1)
    //We have 4 tasks spread across 2 DC and 3 zones
    //We want to launch new task (with  new version).
    //According to constraints it should be placed
    //in pl-east-1b
    val tasks = Set(

      task("1", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("2", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("3", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),

      task("4", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1a")),
      task("5", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1b"))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, tasks, ResourceSelector.wildcard)

    resOpt should not be empty
  }

  test("match resources fail with constraints and old tasks deployed since last config change") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0)
      .addAttributes(TextAttribute("region", "pl-east"))
      .addAttributes(TextAttribute("zone", "pl-east-1b"))
      .build()
    val oldVersion = Timestamp(1)
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      versionInfo = FullVersionInfo(
        version = Timestamp(5),
        lastScalingAt = Timestamp(5),
        lastConfigChangeAt = oldVersion
      ),
      constraints = Set(
        Constraint.newBuilder
          .setField("region")
          .setOperator(Operator.GROUP_BY)
          .setValue("2")
          .build(),
        Constraint.newBuilder
          .setField("zone")
          .setOperator(Operator.GROUP_BY)
          .setValue("4")
          .build()
      )
    )

    //We have 4 tasks spread across 2 DC and 3 zones
    //We want to scale our application.
    //But it will conflict with previously launched tasks.
    val tasks = Set(

      task("1", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("2", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("3", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),

      task("4", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1a")),
      task("5", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1b"))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, tasks, ResourceSelector.wildcard)

    resOpt should be (empty)
  }

  def task(id: String, version: Timestamp, attrs: Map[String, String]): Task = {
    val attributes = attrs.map { case (name, value) => TextAttribute(name, value): Attribute }
    MarathonTestHelper.stagedTask(id, appVersion = version)
      .withAgentInfo(_.copy(attributes = attributes))
  }
}
