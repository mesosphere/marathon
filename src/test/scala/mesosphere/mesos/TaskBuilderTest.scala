package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.{Text, Ranges}
import mesosphere.marathon.api.v1.AppDefinition

import org.junit.Assert._
import org.junit._
import org.mockito.Mockito._

import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.tasks.TaskTracker
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

    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 1
    app.mem = 64
    app.executor = "//cmd"
    app.ports = Seq(8080, 8081)

    val task = buildIfMatches(offer, app)
    assertTrue(task.isDefined)

    val taskInfo = task.get._1
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == TaskBuilder.portsResourceName)
      .map(r => r.getRanges.getRange(0))
    val ports = task.get._2
    assertTrue(range.isDefined)
    assertEquals(2, ports.size)
    assertEquals(ports(0), range.get.getBegin.toInt)
    assertEquals(ports(1), range.get.getEnd.toInt)

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

    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 2
    app.mem = 200
    app.executor = "//cmd"
    app.ports = Seq(8080, 8081)

    val task = buildIfMatches(offer, app)
    assertTrue(task.isDefined)

    val taskInfo = task.get._1
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == TaskBuilder.portsResourceName)
      .map(r => r.getRanges.getRange(0))
    val ports = task.get._2
    assertTrue(range.isDefined)
    assertEquals(2, ports.size)
    assertEquals(ports(0), range.get.getBegin.toInt)
    assertEquals(ports(1), range.get.getEnd.toInt)

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

    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 1
    app.mem = 64
    app.executor = "//cmd"
    app.ports = Seq(8080, 8081)

    val task = buildIfMatches(offer, app)
    assertTrue(task.isDefined)

    val taskInfo = task.get._1
    val range = taskInfo.getResourcesList.asScala
      .find(r => r.getName == TaskBuilder.portsResourceName)
      .map(r => r.getRanges.getRange(0))
    val ports = task.get._2
    assertTrue(range.isDefined)
    assertEquals(2, ports.size)
    assertEquals(ports(0), range.get.getBegin.toInt)
    assertEquals(ports(1), range.get.getEnd.toInt)

    // In this case, the first roles are sufficient so we'll use those first.
    for (r <- taskInfo.getResourcesList.asScala) {
      assertEquals("*", r.getRole)
    }

    // TODO test for resources etc.
  }

  @Test
  def testBuildIfMatchesWithConstraint() {
    val taskTracker =  mock[TaskTracker]

    val offer = makeBasicOffer(1.0, 128.0, 31000, 32000)
      .addAttributes(makeAttribute("rackid", "1"))
      .build

    val app = makeBasicApp()
    app.constraints = Set(("rackid", Constraint.Operator.UNIQUE.toString, None))

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
