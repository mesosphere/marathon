package mesosphere.mesos

import org.junit.Test
import org.junit.Assert._
import org.apache.mesos.Protos._
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.TaskQueue

/**
 * @author Tobi Knaup
 * @author Shingo Omura
 */

class TaskBuilderTest {

  @Test
  def testBuildUtmostTaskFor() {
    val offer = Offer.newBuilder
      .setId(OfferID.newBuilder.setValue("1"))
      .setFrameworkId(FrameworkID.newBuilder.setValue("marathon"))
      .setSlaveId(SlaveID.newBuilder.setValue("slave0"))
      .setHostname("localhost")
      .addResources(TaskBuilder.scalarResource("cpus", 4))
      .addResources(TaskBuilder.scalarResource("mem", 128*4))
      .addResources(TaskBuilder.portsResource(31000L, 32000L))
      .build

    val queue = new TaskQueue
    (0 until 5).map( i => {
      val app = new AppDefinition
      app.id = "testApp"+i
      app.cpus = 1
      app.mem = 128
      queue.add(app)
    })

    val builder = new TaskBuilder(queue, s => TaskID.newBuilder.setValue(s).build)
    val tasks = builder.buildTasks(offer)

    assertTrue(tasks.size == 4)
    (0 until 4).foreach( i => {
      assertTrue(tasks(i)._1.id == "testApp"+i)
      assertTrue(tasks(i)._2.getName == "testApp"+i)
    })
    // TODO test for resources etc.
  }
}