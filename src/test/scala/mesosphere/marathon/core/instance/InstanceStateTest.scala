package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.state.{ Timestamp, UnreachableEnabled, UnreachableStrategy }
import mesosphere.marathon.state.PathId._
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.duration._

class InstanceStateTest extends UnitTest with TableDrivenPropertyChecks {

  "The InstanceState factory" when {
    "passed running instances" should {
      val f = new Fixture

      val startTimestamps = Seq(Some(f.clock.now()), Some(f.clock.now - 1.hour))
      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Running, Condition.Running)
        .values
        .zip(startTimestamps)
        .map {
          case (task, startTime) =>
            val ephemeralTask = task.asInstanceOf[Task.LaunchedEphemeral]
            val newStatus: Task.Status = ephemeralTask.status.copy(startedAt = startTime)
            task.taskId -> ephemeralTask.copy(status = newStatus)
        }(collection.breakOut)

      val state = Instance.InstanceState(None, tasks, f.clock.now(), UnreachableStrategy.default())

      "set the oldest task timestamp as the activeSince timestamp" in { state.activeSince should be(Some(f.clock.now - 1.hour)) }
      "set the instance condition to running" in { state.condition should be(Condition.Running) }
    }

    "passed only staging tasks" should {
      val f = new Fixture

      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Staging, Condition.Staging)
      val state = Instance.InstanceState(None, tasks, f.clock.now(), UnreachableStrategy.default())

      "not set the activeSince timestamp" in { state.activeSince should not be 'defined }
      "set the instance condition to staging" in { state.condition should be(Condition.Staging) }
    }

    "passed one running task and one staging" should {
      val f = new Fixture

      val startTimestamps = Seq(Some(f.clock.now - 1.hour), None)
      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Running, Condition.Staging)
        .values
        .zip(startTimestamps)
        .map {
          case (task, startTime) =>
            val ephemeralTask = task.asInstanceOf[Task.LaunchedEphemeral]
            val newStatus: Task.Status = ephemeralTask.status.copy(startedAt = startTime)
            task.taskId -> ephemeralTask.copy(status = newStatus)
        }(collection.breakOut)

      val state = Instance.InstanceState(None, tasks, f.clock.now(), UnreachableStrategy.default())

      "set the activeSince timestamp to the one from running" in { state.activeSince should be(Some(f.clock.now - 1.hour)) }
      "set the instance condition to staging" in { state.condition should be(Condition.Staging) }
    }

    "passed a running and an unreachable task" should {
      val f = new Fixture

      val startTimestamps = Seq(Some(f.clock.now - 1.hour), None)
      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Running, Condition.Unreachable)
        .values
        .zip(startTimestamps)
        .map {
          case (task, startTime) =>
            val ephemeralTask = task.asInstanceOf[Task.LaunchedEphemeral]
            val newStatus: Task.Status = ephemeralTask.status.copy(startedAt = startTime)
            task.taskId -> ephemeralTask.copy(status = newStatus)
        }(collection.breakOut)

      val state = Instance.InstanceState(None, tasks, f.clock.now(), UnreachableStrategy.default())

      "set the activeSince timestamp to the one from running" in { state.activeSince should be(Some(f.clock.now - 1.hour)) }
      "set the instance condition to unreachable" in { state.condition should be(Condition.Unreachable) }
    }
  }

  "Instance conditionFromTasks" when {
    import Condition._

    val usuals = Seq(
      Created,
      Reserved,
      Running,
      Finished,
      Killed
    )

    val unusuals = Seq(
      Error,
      Failed,
      Gone,
      Dropped,
      Unreachable,
      Killing,
      Starting,
      Staging,
      Unknown
    )

    val product = for (usual <- usuals; unusual <- unusuals) yield (unusual, unusual, usual)

    val cases = Table(("expected", "condition1", "condition2"), product: _*)

    forAll(cases) { (expected, condition1, condition2) =>
      val conditions = Seq(condition1, condition2)

      s"passed conditions ${conditions.mkString(", ")}" should {
        val f = new Fixture()

        val tasks = f.tasks(conditions).values

        val actualCondition = Instance.InstanceState.conditionFromTasks(
          tasks, f.clock.now, UnreachableEnabled(5.minutes))

        s"return condition $expected" in { actualCondition should be(expected) }
      }
    }

  }

  it should {
    "return Unknown for an empty task list" in {
      val f = new Fixture()
      val result = Instance.InstanceState.conditionFromTasks(
        Iterable.empty, f.clock.now(), UnreachableEnabled(5.minutes))

      result should be(Condition.Unknown)
    }
  }

  class Fixture {
    val id = "/test".toPath
    val clock = new SettableClock()

    def tasks(statuses: Condition*): Map[Task.Id, Task] = tasks(statuses.to[Seq])

    def tasks(statuses: Seq[Condition]): Map[Task.Id, Task] =
      statuses.map { status =>
        val taskId = Task.Id.forRunSpec(id)
        val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(status, taskId, Timestamp.now)
        val task = TestTaskBuilder.Helper.minimalTask(taskId, Timestamp.now(), mesosStatus, status)
        task.taskId -> task
      }(collection.breakOut)
  }
}
