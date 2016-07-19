package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
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
    val versionedKey = s"root:${group.version}"
    val appRepo = mock[AppEntityRepository]

    when(store.store(versionedKey, group)).thenReturn(future)
    when(store.store("root", group)).thenReturn(future)

    val metrics = new Metrics(new MetricRegistry)
    val repo = new GroupEntityRepository(store, None, metrics)
    val res = repo.storeRoot(group)

    verify(store).store(versionedKey, group)
    verify(store).store(s"root", group)
  }

  test("group back and forth again with rolling strategy") {
    val group = Group("g1".toPath, Map.empty)
    val proto = group.toProto
    val merged = Group.fromProto(proto)
    group should be(merged)
  }
}
