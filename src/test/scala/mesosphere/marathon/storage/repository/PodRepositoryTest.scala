package mesosphere.marathon.storage.repository

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId

import scala.concurrent.duration.Duration

// small test to make sure pod serialization/deserialization in ZK is functioning.
class PodRepositoryTest extends AkkaUnitTest with ZookeeperServerTest {
  import PathId._

  "PodRepository" should {
    "store and retrieve pods" in {
      val pod = PodDefinition("a".toRootPath)
      val f = new Fixture()
      f.repo.store(pod).futureValue
      f.repo.get(pod.id).futureValue.value should equal(pod)
    }
  }

  class Fixture {
    implicit val metrics = new Metrics(new MetricRegistry)
    val root = UUID.randomUUID().toString
    val rootClient = zkClient(namespace = Some(root))
    val store = new ZkPersistenceStore(rootClient, Duration.Inf)
    val repo = PodRepository.zkRepository(store)
  }
}
