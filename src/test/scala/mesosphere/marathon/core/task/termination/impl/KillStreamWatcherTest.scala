package mesosphere.marathon
package core.task.termination.impl

import java.util.UUID

import akka.Done
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.PrefixInstance
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream.Sink

class KillStreamWatcherTest extends AkkaUnitTest {
  "killedInstanceFlow yields Done immediately when waiting on empty instance Ids" in {
    val result = Source.empty.
      via(KillStreamWatcher.killedInstanceFlow(Nil)).
      runWith(Sink.head)

    result.futureValue shouldBe Done
  }

  "killedInstanceFlow yields Done when all instance Ids are seen" in {

    val instanceIds = List("a", "b", "c").map(appId => Instance.Id(PathId(appId), PrefixInstance, UUID.randomUUID()))
    val otherInstanceIds = List("e", "g", "f").map(appId => Instance.Id(PathId(appId), PrefixInstance, UUID.randomUUID()))

    val result = Source(otherInstanceIds ++ instanceIds).
      via(KillStreamWatcher.killedInstanceFlow(instanceIds)).
      runWith(Sink.head)

    result.futureValue shouldBe Done
  }

  "killedInstanceFlow does not yield Done when not all instance Ids are seen" in {
    val instanceIds = List("a", "b", "c").map(appId => Instance.Id(PathId(appId), PrefixInstance, UUID.randomUUID()))

    val result = Source(instanceIds.tail).
      via(KillStreamWatcher.killedInstanceFlow(instanceIds)).
      runWith(Sink.seq)

    result.futureValue shouldBe Nil
  }
}
