package mesosphere.marathon
package storage.repository

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId

// small test to make sure pod serialization/deserialization in ZK is functioning.
class PodRepositoryTest extends AkkaUnitTest {
  import PathId._

  "PodRepository" should {
    val someContainers = Seq(MesosContainer(name = "foo", resources = Resources()))

    "store and retrieve pods" in {
      val pod = PodDefinition("a".toRootPath, containers = someContainers)
      val f = new Fixture()
      f.repo.store(pod).futureValue
      f.repo.get(pod.id).futureValue.value should equal(pod)
    }
    "store and retrieve pods with executor resources" in {
      val pod = PodDefinition("a".toRootPath, containers = someContainers, executorResources = PodDefinition.DefaultExecutorResources.copy(cpus = 10))
      val f = new Fixture()
      f.repo.store(pod).futureValue
      f.repo.get(pod.id).futureValue.value should equal(pod)
    }
  }

  class Fixture {
    implicit val ctx = ExecutionContexts.global

    val store = new InMemoryPersistenceStore()
    val repo = PodRepository.inMemRepository(store)
  }
}
