package mesosphere.util.state.mesos

import java.util.UUID
import java.util.concurrent.TimeUnit

import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.storage.repository.legacy.store.MesosStateStore
import mesosphere.util.state.PersistentStoreTest
import org.apache.mesos.state.ZooKeeperState
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class MesosStateStoreTest extends PersistentStoreTest with ZookeeperServerTest with Matchers with ScalaFutures {

  //
  // See PersistentStoreTests for general store tests
  //

  lazy val persistentStore: MesosStateStore = {
    // by creating a namespaced client, we know ZK is up
    zkClient(namespace = Some(suiteName))
    val duration = 30.seconds
    val state = new ZooKeeperState(
      zkServer.connectUri,
      duration.toMillis,
      TimeUnit.MILLISECONDS,
      s"/${UUID.randomUUID}"
    )
    new MesosStateStore(state, duration)
  }
}
