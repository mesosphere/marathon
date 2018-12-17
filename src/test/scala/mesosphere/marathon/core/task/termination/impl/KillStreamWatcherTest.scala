package mesosphere.marathon
package core.task.termination.impl

import java.util.UUID

import akka.Done
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.instance.Instance.PrefixInstance
import mesosphere.marathon.state.PathId

import scala.concurrent.duration._

class KillStreamWatcherTest extends AkkaUnitTest {
  "killedInstanceFlow yields Done immediately when waiting on empty instance Ids" in {
    val result = Source.empty.
      via(KillStreamWatcher.killedInstanceFlow(Nil)).
      runWith(Sink.head)

    result.futureValue shouldBe Done
  }

  "killedInstanceFlow yields Done when all instance Ids are seen" in {

    val instanceIds = List("/a", "/b", "/c").map(appId => Instance.Id(PathId(appId), PrefixInstance, UUID.randomUUID()))
    val otherInstanceIds = List("/e", "/g", "/f").map(appId => Instance.Id(PathId(appId), PrefixInstance, UUID.randomUUID()))

    val result = Source(otherInstanceIds ++ instanceIds).
      via(KillStreamWatcher.killedInstanceFlow(instanceIds)).
      runWith(Sink.head)

    result.futureValue shouldBe Done
  }

  "killedInstanceFlow does not yield Done when not all instance Ids are seen" in {
    val instanceIds = List("/a", "/b", "/c").map(appId => Instance.Id(PathId(appId), PrefixInstance, UUID.randomUUID()))

    val result = Source(instanceIds.tail).
      via(KillStreamWatcher.killedInstanceFlow(instanceIds)).
      runWith(Sink.seq)

    result.futureValue shouldBe Nil
  }

  "KillStreamWatcher emits already terminated instances" in {

    val empty = TestInstanceBuilder.emptyInstance(instanceId = Instance.Id.forRunSpec(PathId("/test")))
    val unreachableInstance = empty.copy(state = empty.state.copy(condition = Condition.Killed))

    val watcher = KillStreamWatcher.watchForKilledInstances(system.eventStream, List(unreachableInstance))

    watcher.runWith(Sink.head).futureValue shouldEqual Done
  }

  "KillStreamWatcher doesn't emit anything if there are no changes" in {

    val probe = TestProbe("KillStreamWatcher")

    val empty = TestInstanceBuilder.emptyInstance(instanceId = Instance.Id.forRunSpec(PathId("/test")))
    val runnningInstance = empty.copy(state = empty.state.copy(condition = Condition.Running))

    val watcher = KillStreamWatcher.watchForKilledInstances(system.eventStream, List(runnningInstance))

    watcher.runWith(Sink.head).onComplete(_ => probe.ref ! Done)
    probe.expectNoMessage(100.millis)
  }

}
