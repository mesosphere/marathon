package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.{Text, Ranges}
import mesosphere.marathon.api.v1.AppDefinition

import org.junit.Assert._
import org.junit._
import org.mockito.Mockito._

import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.tasks.{MarathonTasks, TaskTracker}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import scala.collection.mutable
import scala.collection.JavaConverters._
import com.google.common.collect.Lists
import mesosphere.marathon.MarathonTestHelper

/**
 * @author Tobi Knaup
 */

class TaskBuilderTest extends AssertionsForJUnit
  with MockitoSugar with MarathonTestHelper {

  @Test
  def testBuildIfMatches() {
    val offer = makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addResources(TaskBuilder.scalarResource("cpus", 1))
      .addResources(TaskBuilder.scalarResource("mem", 128))
      .build

    val task: Option[(TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp",
        cpus = 1,
        mem = 64,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assertTrue(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == TaskBuilder.portsResourceName)
      .map(r => r.getRanges.getRange(0))
    assertTrue(range.isDefined)
    assertEquals(2, taskPorts.size)
    assertEquals(taskPorts(0), range.get.getBegin.toInt)
    assertEquals(taskPorts(1), range.get.getEnd.toInt)

    for (r <- taskInfo.getResourcesList.asScala) {
      assertEquals("*", r.getRole)
    }

    // TODO test for resources etc.
  }

  @Test
  def testBuildIfMatchesWithRole() {
    val offer = makeBasicOfferWithRole(1.0, 128.0, 31000, 32000, "marathon")
      .addResources(TaskBuilder.scalarResource("cpus", 1, "*"))
      .addResources(TaskBuilder.scalarResource("mem", 128, "*"))
      .addResources(TaskBuilder.scalarResource("cpus", 2, "marathon"))
      .addResources(TaskBuilder.scalarResource("mem", 256, "marathon"))
      .addResources(makePortsResource(Seq((33000, 34000)), "marathon"))
      .build

    val task: Option[(TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp",
        cpus = 2,
        mem = 200,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assertTrue(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == TaskBuilder.portsResourceName)
      .map(r => r.getRanges.getRange(0))
    assertTrue(range.isDefined)
    assertEquals(2, taskPorts.size)
    assertEquals(taskPorts(0), range.get.getBegin.toInt)
    assertEquals(taskPorts(1), range.get.getEnd.toInt)

    for (r <- taskInfo.getResourcesList.asScala) {
      assertEquals("marathon", r.getRole)
    }

    // TODO test for resources etc.
  }

  @Test
  def testBuildIfMatchesWithRole2() {
    val offer = makeBasicOfferWithRole(1.0, 128.0, 31000, 32000, "*")
      .addResources(TaskBuilder.scalarResource("cpus", 1, "*"))
      .addResources(TaskBuilder.scalarResource("mem", 128, "*"))
      .addResources(TaskBuilder.scalarResource("cpus", 2, "marathon"))
      .addResources(TaskBuilder.scalarResource("mem", 256, "marathon"))
      .addResources(makePortsResource(Seq((33000, 34000)), "marathon"))
      .build

    val task: Option[(TaskInfo, Seq[Int])] = buildIfMatches(
      offer,
      AppDefinition(
        id = "testApp",
        cpus = 1,
        mem = 64,
        executor = "//cmd",
        ports = Seq(8080, 8081)
      )
    )

    assertTrue(task.isDefined)

    val (taskInfo, taskPorts) = task.get
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == TaskBuilder.portsResourceName)
      .map(r => r.getRanges.getRange(0))
    assertTrue(range.isDefined)
    assertEquals(2, taskPorts.size)
    assertEquals(taskPorts(0), range.get.getBegin.toInt)
    assertEquals(taskPorts(1), range.get.getEnd.toInt)

    // In this case, the first roles are sufficient so we'll use those first.
    for (r <- taskInfo.getResourcesList.asScala) {
      assertEquals("*", r.getRole)
    }

    // TODO test for resources etc.
  }

  @Test
  def testBuildIfMatchesWithRackIdConstraint() {
    val taskTracker =  mock[TaskTracker]

    val offer = makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addAttributes(makeAttribute("rackid", "1"))
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
      s => TaskID.newBuilder.setValue(s).build, taskTracker)
    val task = builder.buildIfMatches(offer)

    assertTrue(task.isDefined)
    // TODO test for resources etc.
  }

  @Test
  def testRackAndHostConstraints() {
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
      s => TaskID.newBuilder.setValue(s).build, taskTracker)

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assertTrue(message, tupleOption.isDefined)
      val marathonTask = MarathonTasks.makeTask(
        tupleOption.get._1.getTaskId.getValue,
        offer.getHostname,
        tupleOption.get._2,
        offer.getAttributesList.asScala.toList)
      runningTasks.add(marathonTask)
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assertFalse(message, tupleOption.isDefined)
    }

    val offerRack1HostA = makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(makeAttribute("rackid", "1"))
      .build
    shouldBuildTask("Should take first offer", offerRack1HostA)

    val offerRack1HostB = makeBasicOffer()
      .setHostname("beta")
      .addAttributes(makeAttribute("rackid", "1"))
      .build
    shouldNotBuildTask("Should not take offer for the same rack", offerRack1HostB)

    val offerRack2HostC = makeBasicOffer()
      .setHostname("gamma")
      .addAttributes(makeAttribute("rackid", "2"))
      .build
    shouldBuildTask("Should take offer for different rack", offerRack2HostC)

    // Nothing prevents having two hosts with the same name in different racks
    val offerRack3HostA = makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(makeAttribute("rackid", "3"))
      .build
    shouldNotBuildTask("Should not take offer in different rack with non-unique hostname", offerRack3HostA)
  }

  @Test
  def testUniqueHostNameAndClusterAttribute() {
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
      s => TaskID.newBuilder.setValue(s).build, taskTracker)

    def shouldBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assertTrue(message, tupleOption.isDefined)
      val marathonTask = MarathonTasks.makeTask(
        tupleOption.get._1.getTaskId.getValue,
        offer.getHostname,
        tupleOption.get._2,
        offer.getAttributesList.asScala.toList)
      runningTasks.add(marathonTask)
    }

    def shouldNotBuildTask(message: String, offer: Offer) {
      val tupleOption = builder.buildIfMatches(offer)
      assertFalse(message, tupleOption.isDefined)
    }

    val offerHostA = makeBasicOffer()
      .setHostname("alpha")
      .addAttributes(makeAttribute("spark", "disabled"))
      .build
    shouldNotBuildTask("Should not take an offer with spark:disabled", offerHostA)

    val offerHostB = makeBasicOffer()
      .setHostname("beta")
      .addAttributes(makeAttribute("spark", "enabled"))
      .build
    shouldBuildTask("Should take offer with spark:enabled", offerHostB)
  }

  @Test
  def testGetPortsSingleRange() = {
    val portsResource = makePortsResource(Seq((31000, 32000)))
    val portRanges = TaskBuilder.getPorts(portsResource, 2).get

    assertEquals(1, portRanges.size)
    assertEquals(1, portRanges.head._2 - portRanges.head._1)
  }

  @Test
  def testGetPortsMultipleRanges() = {
    val portsResource = makePortsResource(Seq((30000, 30003), (31000, 31009)))
    val portRanges = TaskBuilder.getPorts(portsResource, 5).get

    assertEquals(1, portRanges.size)
    assertEquals(4, portRanges.head._2 - portRanges.head._1)
  }

  @Test
  def testGetNoPorts() {
    val portsResource = makePortsResource(Seq((31000, 32000)))
    assertEquals(Some(Seq()), TaskBuilder.getPorts(portsResource, 0))
  }

  @Test
  def testGetTooManyPorts() {
    val portsResource = makePortsResource(Seq((31000, 32000)))
    assertEquals(None, TaskBuilder.getPorts(portsResource, 10002))
  }

  @Test
  def testPortsEnv() {
    val env = TaskBuilder.portsEnv(Seq(1001, 1002))
    assertEquals("1001", env("PORT"))
    assertEquals("1001", env("PORT0"))
    assertEquals("1002", env("PORT1"))
  }

  @Test
  def testPortsEnvEmpty() {
    val env = TaskBuilder.portsEnv(Seq())
    assertEquals(Map.empty, env)
  }

  def buildIfMatches(offer: Offer, app: AppDefinition) = {
    val taskTracker =  mock[TaskTracker]
    val builder = new TaskBuilder(app,
      s => TaskID.newBuilder.setValue(s).build, taskTracker)
    builder.buildIfMatches(offer)
  }

  def makeSampleTask(id: String, attr: String, attrVal: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .addAttributes(
      Attribute.newBuilder()
        .setName(attr)
        .setText(Text.newBuilder()
        .setValue(attrVal))
        .setType(org.apache.mesos.Protos.Value.Type.TEXT)
        .build())
      .build()
  }
}
