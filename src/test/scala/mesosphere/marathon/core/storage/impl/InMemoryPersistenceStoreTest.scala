/*package mesosphere.marathon.core.storage.impl

import java.util.UUID

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.PersistenceStoreTest

/
class InMemoryPersistenceStoreTest extends AkkaUnitTest with PersistenceStoreTest
    with InMemoryStoreSerialization {
  val rootId: String = ""
  def createId: String = s"${UUID.randomUUID().toString}"

  val store = new InMemoryPersistenceStore()
  "InMemoryPersistenceStore" should {
    behave like singleTypeStore(store)
  }
}
*/