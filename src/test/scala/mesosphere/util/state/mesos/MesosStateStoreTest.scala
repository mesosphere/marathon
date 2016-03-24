package mesosphere.util.state.mesos

import java.util.concurrent.TimeUnit

import mesosphere.marathon.integration.setup.StartedZookeeper
import mesosphere.util.state.PersistentStoreTest
import org.apache.mesos.state.ZooKeeperState
import org.scalatest.{ ConfigMap, Matchers }

import scala.concurrent.duration._

class MesosStateStoreTest extends PersistentStoreTest with StartedZookeeper with Matchers {

  //
  // See PersistentStoreTests for general store tests
  //

  lazy val persistentStore: MesosStateStore = {
    val duration = 30.seconds
    val state = new ZooKeeperState(
      config.zkHostAndPort,
      duration.toMillis,
      TimeUnit.MILLISECONDS,
      config.zkPath
    )
    new MesosStateStore(state, duration)
  }

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap + ("zkPort" -> "2186"))
    Thread.sleep(1000) //zookeeper is up and running. if I try to connect immediately, it will fail
  }
}
