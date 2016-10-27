package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp

import scala.concurrent.duration._

class InstanceTest extends UnitTest {

  "The instance condition" when {
    val stateChangeCases = Seq(
      (Created, Created, Seq(Created, Created, Created)),
      (Created, Staging, Seq(Created, Created, Staging)),
      (Staging, Staging, Seq(Running, Staging, Running)),
      (Running, Running, Seq(Running, Finished, Running)),
      (Running, Failed, Seq(Staging, Starting, Running, Killing, Finished, Failed)),
      (Running, Killing, Seq(Staging, Starting, Running, Killing, Finished)),
      (Running, Error, Seq(Staging, Starting, Running, Killing, Finished, Failed, Error)),
      (Staging, Staging, Seq(Staging)),
      (Running, Gone, Seq(Gone, Running, Running)),
      (Killing, Killed, Seq(Killed, Killed, Killed)),
      (Running, Killing, Seq(Running, Killing, Killed)),
      (Running, Gone, Seq(Running, Gone, Dropped)),
      (Running, Dropped, Seq(Unreachable, Dropped))
    )

    stateChangeCases.foreach {
      case (from, to, withTasks) =>
        val f = new Fixture

        val (instance, tasks) = f.instanceWith(from, withTasks)

        s"$from and tasks become ${withTasks.mkString(", ")}" should {

          val status = Instance.newInstanceState(Some(instance.state), tasks, f.clock.now())

          s"change to $to" in {
            status.condition should be(to)
          }
        }
    }
  }

  "The instance activeSince timestamp" when {

    "all tasks are started" should {
      val f = new Fixture

      val startTimestamps = Seq(Some(f.clock.now), Some(f.clock.now - 1.hour))
      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Running, Condition.Running)
        .values
        .zip(startTimestamps)
        .map {
          case (task, startTime) =>
            val ephemeralTask = task.asInstanceOf[Task.LaunchedEphemeral]
            val newStatus: Task.Status = ephemeralTask.status.copy(startedAt = startTime)
            task.taskId -> ephemeralTask.copy(status = newStatus)
        }(collection.breakOut)

      val state = InstanceState(Condition.Running, f.clock.now(), None)
      val instance = Instance(Instance.Id.forRunSpec(f.id), f.agentInfo, state, tasks, runSpecVersion = f.clock.now)

      "give the oldest " in { instance.activeSince should be(Some(f.clock.now - 1.hour)) }
    }

    "no task is started" should {
      val f = new Fixture

      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Staging, Condition.Starting)
      val state = InstanceState(Condition.Staging, f.clock.now(), None)
      val instance = Instance(Instance.Id.forRunSpec(f.id), f.agentInfo, state, tasks, runSpecVersion = f.clock.now)

      "give not activeSince timestamp" in { instance.activeSince should not be 'defined }
    }

    "one task is not start but another is" should {
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

      val state = InstanceState(Condition.Running, f.clock.now(), None)
      val instance = Instance(Instance.Id.forRunSpec(f.id), f.agentInfo, state, tasks, runSpecVersion = f.clock.now)

      "give the oldest of the started tasks" in { instance.activeSince should be(Some(f.clock.now - 1.hour)) }
    }
  }

  class Fixture {
    val id = "/test".toPath
    val clock = ConstantClock()

    val agentInfo = Instance.AgentInfo("", None, Nil)
    def tasks(statuses: Condition*): Map[Task.Id, Task] = tasks(statuses.to[Seq])
    def tasks(statuses: Seq[Condition]): Map[Task.Id, Task] =
      statuses.map { status =>
        val task = TestTaskBuilder.Helper.minimalTask(Task.Id.forRunSpec(id), Timestamp.now(), None, status)
        task.taskId -> task
      }(collection.breakOut)

    def instanceWith(condition: Condition, conditions: Seq[Condition]): (Instance, Map[Task.Id, Task]) = {
      val state = InstanceState(condition, Timestamp.now(), None)
      val currentTasks = tasks(conditions.map(_ => condition))
      val newTasks = tasks(conditions)
      val instance = Instance(Instance.Id.forRunSpec(id), agentInfo, state, currentTasks, runSpecVersion = Timestamp.now())
      (instance, newTasks)
    }
  }
}
