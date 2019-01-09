package mesosphere.marathon
package core.task.termination.impl

import akka.Done
import akka.stream.scaladsl.{Flow, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId

class KillStreamWatcherTest extends AkkaUnitTest {
  val instancesTerminalFlow = Flow[Instance].map { i => InstanceUpdated(i.copy(state = i.state.copy(condition = Condition.Finished)), Some(i.state), Nil) }

  val instancesDecommissionedFlow = Flow[Instance].map { i =>
    InstanceDeleted(i, None, Nil)
  }
  "killedInstanceFlow yields Done immediately when waiting on empty instance Ids" in {
    val emptyUpdates: InstanceTracker.InstanceUpdates = Source.single((InstancesSnapshot(Nil), Source.empty))
    val result = KillStreamWatcher.watchForKilledInstances(emptyUpdates, Set.empty)

    result.futureValue shouldBe Done
  }

  "killedInstanceFlow yields Set.empty when all instance Ids are seen" in {

    val instances = List("/a", "/b", "/c").map(appId => TestInstanceBuilder.newBuilder(PathId(appId)).addTaskRunning().instance)
    val otherInstance = List("/e", "/g", "/f").map(appId => TestInstanceBuilder.newBuilder(PathId(appId)).addTaskRunning().instance)

    val instanceUpdates = Source.single(InstancesSnapshot(instances ++ otherInstance) -> Source(instances).via(instancesTerminalFlow))

    val result = KillStreamWatcher.emitPendingTerminal(instanceUpdates, instances).runWith(Sink.last)

    result.futureValue shouldBe Set.empty
  }

  "emitPendingTerminal does not yield Set.empty when not all instance Ids are seen" in {
    val instances = List("/a", "/b", "/c").map(appId => TestInstanceBuilder.newBuilder(PathId(appId)).addTaskRunning().instance)

    val instanceUpdates = Source.single(InstancesSnapshot(instances) -> Source(instances.tail).via(instancesTerminalFlow))

    val result = KillStreamWatcher.emitPendingTerminal(instanceUpdates, instances).runWith(Sink.last)

    result.futureValue.map(_.runSpecId) shouldBe Set(PathId("/a"))
  }

  "KillStreamWatcher recognizes instances that are already terminal" in {

    val unreachableInstance = TestInstanceBuilder.newBuilder(PathId("/test")).addTaskUnreachable().instance
    val instanceUpdates = Source.single(InstancesSnapshot(Seq(unreachableInstance)) -> Source.empty)
    val result = KillStreamWatcher.emitPendingTerminal(instanceUpdates, Seq(unreachableInstance)).runWith(Sink.last)

    result.futureValue shouldBe Set.empty
  }

  "KillStreamWatcher emits the original instances if nothing is terminal" in {
    val instances = List("/a", "/b", "/c").map(appId => TestInstanceBuilder.newBuilder(PathId(appId)).addTaskRunning().instance)
    val instanceUpdates = Source.single(InstancesSnapshot(instances) -> Source.empty)

    val result = KillStreamWatcher.emitPendingTerminal(instanceUpdates, instances).runWith(Sink.seq)

    result.futureValue.map(_.map(_.runSpecId)) shouldBe List(Set(PathId("/a"), PathId("/b"), PathId("/c")))
  }

  "KillStreamWatcher treats instances with taskIds with later incarnations as terminal" in {
    val instanceWithIncarnation1 = TestInstanceBuilder.newBuilder(PathId("/a")).addTaskRunning().instance
    val instanceWithIncarnation2 = instanceWithIncarnation1.copy(tasksMap = instanceWithIncarnation1.tasksMap.map {
      case (taskId, task) =>
        val nextTaskId = Task.Id.nextIncarnationFor(taskId)
        nextTaskId -> task.copy(taskId = nextTaskId)
    })
    val instanceUpdates = Source.single(InstancesSnapshot(Seq(instanceWithIncarnation2)) -> Source.empty)

    val result1 = KillStreamWatcher.emitPendingTerminal(instanceUpdates, Seq(instanceWithIncarnation1)).runWith(Sink.last)

    result1.futureValue shouldBe Set.empty

    val result2 = KillStreamWatcher.emitPendingTerminal(instanceUpdates, Seq(instanceWithIncarnation2)).runWith(Sink.last)

    result2.futureValue shouldNot be(Set.empty)
  }

  "KillStreamWatcher does not emit instances as terminal if they're terminal, but not decommissioned and expunged" in {
    val instanceWithIncarnation = TestInstanceBuilder.newBuilder(PathId("/a")).addTaskRunning().instance

    val instanceUpdatesTerminalNonDecomissioned = Source.single(
      InstancesSnapshot(Seq(instanceWithIncarnation)) -> Source.single(instanceWithIncarnation).via(instancesTerminalFlow))

    val result1 = KillStreamWatcher.emitPendingDecomissioned(instanceUpdatesTerminalNonDecomissioned, Set(instanceWithIncarnation.instanceId)).runWith(Sink.last)

    result1.futureValue.map(_.runSpecId) shouldBe Set(PathId("/a"))

    val instanceUpdatesTerminalAndDecomissioned = Source.single(
      InstancesSnapshot(Seq(instanceWithIncarnation)) -> Source.single(instanceWithIncarnation).via(instancesDecommissionedFlow))

    val result2 = KillStreamWatcher.emitPendingDecomissioned(instanceUpdatesTerminalAndDecomissioned, Set(instanceWithIncarnation.instanceId)).runWith(Sink.last)

    result2.futureValue should be(Set.empty)
  }
}
