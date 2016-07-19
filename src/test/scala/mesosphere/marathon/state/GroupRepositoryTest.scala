package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.storage.repository.impl.legacy.{ AppEntityRepository, GroupEntityRepository }
import mesosphere.marathon.core.storage.repository.impl.legacy.store.MarathonStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import org.mockito.Mockito._
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future

// TODO(jason): This test should use an actual store.
class GroupRepositoryTest extends MarathonSpec with Matchers with ScalaFutures {

  test("Store canary strategy") {
    val store = mock[MarathonStore[Group]]
    val group = Group("g1".toPath, Map.empty)
    val future = Future.successful(group)
    val versionedKey = s"${GroupEntityRepository.ZkRootName}:${group.version}"
    val appRepo = mock[AppEntityRepository]

    when(store.store(GroupEntityRepository.ZkRootName.safePath, group)).thenReturn(future)

    val metrics = new Metrics(new MetricRegistry)
    val repo = new GroupEntityRepository(store, 0)(metrics = metrics)
    val res = repo.storeRoot(group)

    verify(store).store(GroupEntityRepository.ZkRootName.safePath, group)
  }

  test("group back and forth again with rolling strategy") {
    val group = Group("g1".toPath, Map.empty)
    val proto = group.toProto
    val merged = Group.fromProto(proto)
    group should be(merged)
  }
}
