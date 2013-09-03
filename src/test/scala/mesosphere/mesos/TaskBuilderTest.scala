package mesosphere.mesos

import org.junit.Test
import org.junit.Assert._
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.{Text, Ranges}
import mesosphere.marathon.api.v1.AppDefinition

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
  def testBuildIfMatches() {
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
      .build

    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 1
    app.mem = 64
    app.executor = "//cmd"

    val builder = new TaskBuilder(app,
      s => TaskID.newBuilder.setValue(s).build, null)
    val task = builder.buildIfMatches(offer)

    assertTrue(task.isDefined)
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

    val builder = new TaskBuilder(app,
      s => TaskID.newBuilder.setValue(s).build, taskTracker)
    val task = builder.buildIfMatches(offer)

    assertTrue(task.isDefined)
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
}