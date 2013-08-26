package mesosphere.marathon

import org.junit.Test
import org.junit.Assert._
import mesosphere.marathon.api.v1.AppDefinition
import org.hamcrest.core.Is._
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.Ranges
import mesosphere.mesos.TaskBuilder

/**
 * @author Shingo Omura
 */
class AppResourceTest {

  @Test
  def testlteq {
    // These can be comparable.
    assertTrue(AppResource(1, 1).lteq(AppResource(1, 1)))
    assertTrue(AppResource(1, 1).lteq(AppResource(1, 2)))
    assertTrue(AppResource(1, 1).lteq(AppResource(2, 1)))
    assertTrue(AppResource(1, 1).lteq(AppResource(2, 2)))

    // These can't be comparable.
    assertFalse(AppResource(1, 2).lteq(AppResource(2, 1)))
    assertFalse(AppResource(2, 1).lteq(AppResource(1, 2)))
  }

  @Test
  def testConversions {
    import AppResource._

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
    assertThat(Offer2AppResource(offer), is(AppResource(1.0, 128.0)))

    val offerZero = Offer.newBuilder
      .setId(OfferID.newBuilder.setValue("2"))
      .setFrameworkId(FrameworkID.newBuilder.setValue("marathon"))
      .setSlaveId(SlaveID.newBuilder.setValue("slave0"))
      .setHostname("localhost")
      .build()
    assertThat(Offer2AppResource(offerZero), is(AppResource(0, 0)))

    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 1
    app.mem = 64
    assertThat(AppDefinition2AppResource(app), is(AppResource(1.0, 64.0)))
  }
}
