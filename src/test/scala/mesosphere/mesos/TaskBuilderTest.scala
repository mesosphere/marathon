package mesosphere.mesos

import mesosphere.marathon.api.v1.AppDefinition

import org.mockito.Mockito._

import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.tasks.{MarathonTasks, TaskTracker}
import scala.collection.mutable
import scala.collection.JavaConverters._
import com.google.common.collect.Lists
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{Offer, TaskInfo}
import mesosphere.mesos.protos._

/**
 * @author Tobi Knaup
 */

class TaskBuilderTest extends MarathonSpec {

  import mesosphere.mesos.protos.Implicits._

  test("BuildIfMatches") {
    val offer = makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addResources(ScalarResource("cpus", 1))
      .addResources(ScalarResource("mem", 128))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp",
        cpus = 1,
        mem = 64,
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
      assert("*" == r.getRole)
    }

    // TODO test for resources etc.
  }

  test("BuildIfMatchesWithRole") {
    val offer = makeBasicOfferWithRole(1.0, 128.0, 31000, 32000, "marathon")
      .addResources(ScalarResource("cpus", 1, "*"))
      .addResources(ScalarResource("mem", 128, "*"))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp",
        cpus = 2,
        mem = 200,
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
    val offer = makeBasicOfferWithRole(1.0, 128.0, 31000, 32000, "*")
      .addResources(ScalarResource("cpus", 1, "*"))
      .addResources(ScalarResource("mem", 128, "*"))
      .addResources(ScalarResource("cpus", 2, "marathon"))
      .addResources(ScalarResource("mem", 256, "marathon"))
      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
      .build

    val task: Option[(TaskInfo, Seq[Long])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp",
        cpus = 1,
        mem = 64,
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
    val taskTracker =  mock[TaskTracker]

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
    val s = mutable.Set(t1, t2)

    when(taskTracker.get(app.id)).thenReturn(s)

    val builder = new TaskBuilder(app,
      s => TaskID(s), taskTracker)
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

    val runningTasks = new mutable.HashSet[MarathonTask]()
    val taskTracker = mock[TaskTracker]
    when(taskTracker.get(app.id)).thenReturn(runningTasks)

    val builder = new TaskBuilder(app,
      s => TaskID(s), taskTracker)

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assert(tupleOption.isDefined, message)
      val marathonTask = MarathonTasks.makeTask(
        tupleOption.get._1.getTaskId.getValue,
        offer.getHostname,
        tupleOption.get._2,
        offer.getAttributesList.asScala.toList,
        Timestamp.now)
      runningTasks.add(marathonTask)
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

    val runningTasks = new mutable.HashSet[MarathonTask]()
    val taskTracker = mock[TaskTracker]
    when(taskTracker.get(app.id)).thenReturn(runningTasks)

    val builder = new TaskBuilder(app,
      s => TaskID(s), taskTracker)

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assert(tupleOption.isDefined, message)
      val marathonTask = MarathonTasks.makeTask(
        tupleOption.get._1.getTaskId.getValue,
        offer.getHostname,
        tupleOption.get._2,
        offer.getAttributesList.asScala.toList, Timestamp.now)
      runningTasks.add(marathonTask)
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

  test("GetPortsSingleRange") {
    val offered = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31000, 32000))
    )
    val portRanges = TaskBuilder.getPorts(offered, 2).get.ranges

    assert(1 == portRanges.size)
    assert(2 == portRanges.head.asScala.size)
  }

  test("GetPortsMultipleRanges") {
    val offered = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003), protos.Range(31000, 31009))
    )
    val portRanges = TaskBuilder.getPorts(offered, 5).get.ranges

    assert(1 == portRanges.size)
    assert(5 == portRanges.head.asScala.size)
  }

  test("GetNoPorts") {
    val portsResource = RangesResource(Resource.PORTS, Seq(protos.Range(31000, 32000)))
    assert(
      Some(RangesResource(Resource.PORTS, Seq())) ==
        TaskBuilder.getPorts(portsResource, 0)
    )
  }

  test("GetTooManyPorts") {
    val portsResource = RangesResource(Resource.PORTS, Seq(protos.Range(31000, 32000)))
    assert(TaskBuilder.getPorts(portsResource, 10002).isEmpty)
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

  def buildIfMatches(offer: Offer, app: AppDefinition) = {
    val taskTracker =  mock[TaskTracker]
    val builder = new TaskBuilder(app,
      s => TaskID(s), taskTracker)
    builder.buildIfMatches(offer)
  }

  def makeSampleTask(id: String, attr: String, attrVal: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .addAttributes(TextAttribute(attr, attrVal))
      .build()
  }
}
