package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.{ScalingStrategy, Group}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import org.mockito.Mockito._

class GroupRepositoryTest extends MarathonSpec {
  test("Store") {
    val store = mock[MarathonStore[Group]]
    val group = Group("g1", ScalingStrategy(Seq.empty, 23), Seq.empty)
    val future = Future.successful(Some(group))
    val versionedKey = s"g1:${group.version}"

    when(store.store(versionedKey, group)).thenReturn(future)
    when(store.store("g1", group)).thenReturn(future)

    val repo = new GroupRepository(store)
    val res = repo.store(group)

    assert(Some(group) == Await.result(res, 5 seconds), "Should return the correct AppDefinition")
    verify(store).store(versionedKey, group)
    verify(store).store(s"g1", group)
  }
}
