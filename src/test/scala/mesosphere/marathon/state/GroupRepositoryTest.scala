package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import org.mockito.Mockito._
import org.scalatest.Matchers
import mesosphere.marathon.api.v2.{Group, ScalingStrategy}

class GroupRepositoryTest extends MarathonSpec with Matchers {

  test("Store canary strategy") {
    val store = mock[MarathonStore[Group]]
    val group = Group("g1", ScalingStrategy(1, None), Seq.empty)
    val future = Future.successful(Some(group))
    val versionedKey = s"g1:${group.version}"
    val appRepo = mock[AppRepository]

    when(store.store(versionedKey, group)).thenReturn(future)
    when(store.store("g1", group)).thenReturn(future)

    val repo = new GroupRepository(store, appRepo)
    val res = repo.store(group)

    assert(group == Await.result(res, 5 seconds), "Should return the correct AppDefinition")
    verify(store).store(versionedKey, group)
    verify(store).store(s"g1", group)
  }

  test("group back and forth again with rolling strategy") {
    val group = Group("g1", ScalingStrategy(1, None), Seq.empty)
    val proto = group.toProto
    val merged = Group.empty().mergeFromProto(proto)
    group should be(merged)
  }
}
