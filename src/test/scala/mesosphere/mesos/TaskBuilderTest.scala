package mesosphere.mesos

import org.junit.Test
import org.junit.Assert._
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.{Text, Ranges}
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskQueue

import org.junit.Assert._
import org.junit._
import org.mockito.Mockito._

import scala.collection.JavaConverters._
import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.tasks.TaskTracker
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import scala.collection.mutable

/**
 * @author Tobi Knaup
 * @author Shingo Omura
 */

class TaskBuilderTest  extends AssertionsForJUnit with MockitoSugar {

  def makeAttribute(attr: String, attrVal: String) = {
    Attribute.newBuilder()
      .setName(attr)
      .setText(Text.newBuilder()
      .setValue(attrVal))
      .setType(org.apache.mesos.Protos.Value.Type.TEXT)
      .build()
  }


  @Test
  def testBuildTasks() {
    val range = Value.Range.newBuilder
      .setBegin(31000L)
      .setEnd(32000L)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range)
      .build
    val portResource = Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build
    val offer = Offer.newBuilder
      .setId(OfferID.newBuilder.setValue("1"))
      .setFrameworkId(FrameworkID.newBuilder.setValue("marathon"))
      .setSlaveId(SlaveID.newBuilder.setValue("slave0"))
      .setHostname("localhost")
      .addResources(TaskBuilder.scalarResource("cpus", 4))
      .addResources(TaskBuilder.scalarResource("mem", 128*4))
      .addResources(portResource)
      .build

    val queue = new TaskQueue
    (0 until 5).map(i => {
      val app = new AppDefinition
      app.id = "testApp"+i
      app.cpus = 1
      app.mem = 128
      app.executor = "//cmd"
      queue.add(app)
    })

    val tracker = mock[TaskTracker]
    when(tracker.newTaskId("testApp0"))
      .thenReturn(TaskID.newBuilder.setValue("testApp0").build)
    when(tracker.newTaskId("testApp1"))
      .thenReturn(TaskID.newBuilder.setValue("testApp1").build)
    when(tracker.newTaskId("testApp2"))
      .thenReturn(TaskID.newBuilder.setValue("testApp2").build)
    when(tracker.newTaskId("testApp3"))
      .thenReturn(TaskID.newBuilder.setValue("testApp3").build)

    val builder = new TaskBuilder(queue, tracker, null)
    val tasks = builder.buildTasks(offer)


    assertTrue(tasks.size == 4)
    (0 until 4).foreach(i => {
      assertTrue(tasks(i)._1.id == "testApp"+i)
      assertTrue(tasks(i)._2.getName == "testApp"+i)
    })
    // TODO test for resources etc.
  }

  @Test
  def testBuildIfMatchesWithConstraint() {
    val taskTracker =  mock[TaskTracker]


    val range = Value.Range.newBuilder
      .setBegin(31000L)
      .setEnd(32000L)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range)
      .build
    val portResource = Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build
    val offer = Offer.newBuilder
      .setId(OfferID.newBuilder.setValue("1"))
      .setFrameworkId(FrameworkID.newBuilder.setValue("marathon"))
      .setSlaveId(SlaveID.newBuilder.setValue("slave0"))
      .setHostname("localhost")
      .addResources(TaskBuilder.scalarResource("cpus", 1))
      .addResources(TaskBuilder.scalarResource("mem", 128))
      .addResources(portResource)
      .addAttributes(makeAttribute("rackid", "1"))
      .build

    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 1
    app.mem = 64
    app.executor = "//cmd"
    app.constraints = Set(("rackid", Constraint.Operator.UNIQUE_VALUE, None))

    val t1 = makeSampleTask(app.id, "rackid", "2")
    val t2 = makeSampleTask(app.id, "rackid", "3")
    val s = mutable.Set(t1, t2)

    when(taskTracker.get(app.id)).thenReturn(s)
    when(taskTracker.newTaskId(app.id))
      .thenReturn(TaskID.newBuilder.setValue(app.id).build)

    val queue = new TaskQueue
    queue.add(app)
    val builder = new TaskBuilder(queue, taskTracker)
    val tasks = builder.buildTasks(offer)

    assertTrue(tasks.size == 1)
    assertTrue(tasks(0)._1 == app)
    // TODO test for resources etc.
  }

  def makeSampleTask(id: String, attr: String, attrVal: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .setPort(999)
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

  @Test
  def testGetPortSingleRange() = {
    val maxPort = 32000L
    val minPort = 31000L

    val range = Value.Range.newBuilder
      .setBegin(minPort)
      .setEnd(maxPort)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range)
      .build
    val portResource = Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build

    val port = TaskBuilder.getPort(portResource)

    assertTrue(port.get > minPort && port.get < maxPort)
    assertEquals(port.get % TaskBuilder.portBlockSize,0)
  }

  @Test
  def testGetPortMultipleRanges() = {
    val maxPort1 = 31009L
    val minPort1 = 31000L

    val maxPort2 = 30003L
    val minPort2 = 30000L

    val range1 = Value.Range.newBuilder
      .setBegin(minPort1)
      .setEnd(maxPort1)
      .build
    val range2 = Value.Range.newBuilder
      .setBegin(minPort2)
      .setEnd(maxPort2)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range1)
      .addRange(range2)
      .build
    val portResource = Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build

    val port = TaskBuilder.getPort(portResource)

    assertTrue(port.get == 31000 || port.get == 31005)
  }

  @Test
  def testGetPortNotEnoughBlocksInRange() = {
    val maxPort1 = 31003L
    val minPort1 = 31000L

    val maxPort2 = 30003L
    val minPort2 = 30000L

    val range1 = Value.Range.newBuilder
      .setBegin(minPort1)
      .setEnd(maxPort1)
      .build
    val range2 = Value.Range.newBuilder
      .setBegin(minPort2)
      .setEnd(maxPort2)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range1)
      .addRange(range2)
      .build
    val portResource = Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build

    val port = TaskBuilder.getPort(portResource)

    assertTrue(port.isEmpty)
  }
}
