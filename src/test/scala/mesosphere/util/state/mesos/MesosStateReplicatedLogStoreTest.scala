package mesosphere.util.state.mesos

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

import mesosphere.marathon.integration.setup.StartedZookeeper
import mesosphere.marathon.io.IO
import mesosphere.util.state.PersistentStoreTest
import org.apache.mesos.state.LogState
import org.scalatest.{ ConfigMap, Matchers }
import scala.sys.process._

import scala.concurrent.duration._

class MesosStateReplicatedLogStoreTest extends PersistentStoreTest with StartedZookeeper with Matchers {

  //
  // See PersistentStoreTests for general store tests
  //

  val replicatedLogFile = "/tmp/replicated_log_test_" + UUID.randomUUID()

  lazy val persistentStore: MesosStateStore = {
    val duration = 30.seconds
    val state = new LogState(
      config.zkHostAndPort,
      duration.toMillis,
      TimeUnit.MILLISECONDS,
      config.zkPath,
      1,
      replicatedLogFile
    )
    new MesosStateStore(state, duration)
  }

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap + ("zkPort" -> "2186"))
    s"mesos-log initialize --path=$replicatedLogFile".!!
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    IO.delete(new File(replicatedLogFile))
  }
}