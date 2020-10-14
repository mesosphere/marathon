package mesosphere.marathon
package core.task.termination.impl

import akka.Done
import akka.stream.scaladsl.{Flow, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.marathon.util.StreamHelpers

class KillStreamWatcherTest extends AkkaUnitTest {
  val instancesTerminalFlow = Flow[Instance].map { i =>
    InstanceUpdated(i.copy(state = i.state.copy(condition = Condition.Finished)), Some(i.state), Nil)
  }

  val instancesExpungedFlow = Flow[Instance].map { i =>
    InstanceDeleted(i, None, Nil)
  }

  val instancesDecommissionedFlow = Flow[Instance].map { i =>
    InstanceUpdated(i.copy(state = i.state.copy(goal = Goal.Decommissioned)), Some(i.state), Nil)
  }

  "watchForKilledInstances" should {
    "completes immediately if provided an empty instance Ids" in {
      val emptyUpdates: InstanceTracker.InstanceUpdates = Source.single((InstancesSnapshot(Nil), StreamHelpers.sourceNever))
      val result = KillStreamWatcher
        .watchForKilledTasks(emptyUpdates, Set.empty)
        .runWith(Sink.ignore)
        .futureValue

      result shouldBe Done
    }

    "completes immediately if provided instances are already terminal and no updates arrive" in {
      val terminalInstance = TestInstanceBuilder.newBuilder(AbsolutePathId("/test")).addTaskKilled().instance
      val emptyUpdates: InstanceTracker.InstanceUpdates =
        Source.single((InstancesSnapshot(Seq(terminalInstance)), StreamHelpers.sourceNever))

      val result = KillStreamWatcher
        .watchForKilledTasks(emptyUpdates, Seq(terminalInstance))
        .runWith(Sink.ignore)
        .futureValue

      result shouldBe Done
    }

    "recognizes instances that are already terminal in the initial snapshot" in {
      val unreachableInstance = TestInstanceBuilder.newBuilder(AbsolutePathId("/test")).addTaskUnreachable().instance
      val instanceUpdates = Source.single(InstancesSnapshot(Seq(unreachableInstance)) -> StreamHelpers.sourceNever)
      val result = KillStreamWatcher
        .watchForKilledTasks(instanceUpdates, Seq(unreachableInstance))
        .runWith(Sink.last)
        .futureValue

      result shouldBe Set.empty
    }

    "completes with Set.empty when all instance Ids are seen" in {
      val instances = List("/a", "/b", "/c").map(appId => TestInstanceBuilder.newBuilder(AbsolutePathId(appId)).addTaskRunning().instance)
      val otherInstance =
        List("/e", "/g", "/f").map(appId => TestInstanceBuilder.newBuilder(AbsolutePathId(appId)).addTaskRunning().instance)

      val instanceUpdates = Source.single(InstancesSnapshot(instances ++ otherInstance) -> Source(instances).via(instancesTerminalFlow))

      val result = KillStreamWatcher
        .watchForKilledTasks(instanceUpdates, instances)
        .runWith(Sink.last)
        .futureValue

      result shouldBe Set.empty
    }

    "emitPendingTerminal does not yield Set.empty when not all instance Ids are seen" in {
      val instances = List("/a", "/b", "/c").map(appId => TestInstanceBuilder.newBuilder(AbsolutePathId(appId)).addTaskRunning().instance)

      val instanceUpdates = Source.single(InstancesSnapshot(instances) -> Source(instances.tail).via(instancesTerminalFlow))

      val result = KillStreamWatcher.watchForKilledTasks(instanceUpdates, instances).runWith(Sink.last)

      result.futureValue.map(_.runSpecId) shouldBe Set(AbsolutePathId("/a"))
    }

    "emits the original instances if nothing is terminal" in {
      val instances = List("/a", "/b", "/c").map(appId => TestInstanceBuilder.newBuilder(AbsolutePathId(appId)).addTaskRunning().instance)
      val instanceUpdates = Source.single(InstancesSnapshot(instances) -> Source.empty)

      val Seq(result) = KillStreamWatcher.watchForKilledTasks(instanceUpdates, instances).runWith(Sink.seq).futureValue

      result.map(_.runSpecId) shouldBe Set(AbsolutePathId("/a"), AbsolutePathId("/b"), AbsolutePathId("/c"))
    }

    "treats instances with taskIds with later incarnations as terminal" in {
      val instanceWithIncarnation1 = TestInstanceBuilder.newBuilder(AbsolutePathId("/a")).addTaskRunning().instance
      val instanceWithIncarnation2 = instanceWithIncarnation1.copy(tasksMap = instanceWithIncarnation1.tasksMap.map {
        case (taskId, task) =>
          val nextTaskId = Task.Id.nextIncarnationFor(taskId)
          nextTaskId -> task.copy(taskId = nextTaskId)
      })
      val instanceUpdates = Source.single(InstancesSnapshot(Seq(instanceWithIncarnation2)) -> Source.empty)

      val Seq(result1) = KillStreamWatcher
        .watchForKilledTasks(instanceUpdates, Seq(instanceWithIncarnation1))
        .runWith(Sink.seq)
        .futureValue

      result1 shouldBe Set.empty

      val Seq(result2: Set[Instance.Id]) = KillStreamWatcher
        .watchForKilledTasks(instanceUpdates, Seq(instanceWithIncarnation2))
        .runWith(Sink.seq)
        .futureValue

      result2 shouldNot be(Set.empty)
    }
  }

  "watchForDecommissionedInstances" should {
    "not treat instances as terminal until they are both decommissioned and expunged" in {
      val instance = TestInstanceBuilder.newBuilder(AbsolutePathId("/a")).addTaskRunning().instance

      val instanceUpdatesTerminalNonDecomissioned =
        Source.single(InstancesSnapshot(Seq(instance)) -> Source.single(instance).via(instancesTerminalFlow))

      val result1 = KillStreamWatcher
        .watchForDecommissionedInstances(instanceUpdatesTerminalNonDecomissioned, Seq(instance))
        .runWith(Sink.last)
        .futureValue

      result1.map(_.runSpecId) shouldBe Set(AbsolutePathId("/a"))

      val instanceUpdatesTerminalAndDecommissioned =
        Source.single(InstancesSnapshot(Seq(instance)) -> Source.single(instance).via(instancesExpungedFlow))

      val result2 = KillStreamWatcher
        .watchForDecommissionedInstances(instanceUpdatesTerminalAndDecommissioned, Seq(instance))
        .runWith(Sink.last)
        .futureValue

      result2 should be(Set.empty)
    }

    "treats unreachable instances with goal decommissioned as terminal" in {
      val instance = TestInstanceBuilder.newBuilder(AbsolutePathId("/a")).addTaskUnreachable().instance

      val instanceUpdates = Source.single(InstancesSnapshot(Seq(instance)) -> Source.single(instance).via(instancesDecommissionedFlow))

      val result = KillStreamWatcher
        .watchForDecommissionedInstances(instanceUpdates, Seq(instance))
        .runWith(Sink.last)
        .futureValue

      result shouldBe Set.empty
    }

    "treats instances as terminal if they went directly from scheduled to expunged" in {
      val scheduledInstance = TestInstanceBuilder.newBuilder(AbsolutePathId("/a")).withInstanceCondition(Condition.Scheduled).instance

      val instanceUpdates = Source.single(
        InstancesSnapshot(Seq(scheduledInstance)) ->
          Source
            .single(scheduledInstance)
            .via(instancesExpungedFlow)
            .concat(StreamHelpers.sourceNever)
      )

      val result = KillStreamWatcher
        .watchForDecommissionedInstances(instanceUpdates, Seq(scheduledInstance))
        .runWith(Sink.last)
        .futureValue

      result shouldBe Set.empty
    }
  }
}
