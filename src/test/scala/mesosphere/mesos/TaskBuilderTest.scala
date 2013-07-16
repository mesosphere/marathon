package mesosphere.mesos

import org.junit.Test
import org.junit.Assert._
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.Ranges
import mesosphere.marathon.api.v1.AppDefinition

/**
 * @author Tobi Knaup
 */

class TaskBuilderTest {

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

    val builder = new TaskBuilder(app, s => TaskID.newBuilder.setValue(s).build)
    val task = builder.buildIfMatches(offer)

    assertTrue(task.isDefined)
    // TODO test for resources etc.
  }
}