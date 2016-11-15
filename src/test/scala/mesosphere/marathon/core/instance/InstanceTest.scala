package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import org.scalatest.prop.TableDrivenPropertyChecks

class InstanceTest extends UnitTest with TableDrivenPropertyChecks {

  "The instance condition" when {

    val stateChangeCases = Table(
      ("from", "to", "withTasks"),
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

    forAll (stateChangeCases) { (from, to, withTasks) =>
      val f = new Fixture

      val (instance, tasks) = f.instanceWith(from, withTasks)

      s"$from and tasks become ${withTasks.mkString(", ")}" should {

        val status = Instance.InstanceState(Some(instance.state), tasks, f.clock.now())

        s"change to $to" in {
          status.condition should be(to)
        }
      }
    }
  }

  "An instance" when {

    val conditions = Table (
      ("condition", "isReserved", "isCreated", "isError", "isFailed", "isFinished", "isKilled", "isKilling", "isRunning", "isStaging", "isStarting", "isUnreachable", "isGone", "isUnknown", "isDropped", "isTerminated", "isActive"),
      (Reserved, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false),
      (Created, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, true),
      (Error, false, false, true, false, false, false, false, false, false, false, false, false, false, false, true, false),
      (Failed, false, false, false, true, false, false, false, false, false, false, false, false, false, false, true, false),
      (Finished, false, false, false, false, true, false, false, false, false, false, false, false, false, false, true, false),
      (Killed, false, false, false, false, false, true, false, false, false, false, false, false, false, false, true, false),
      (Killing, false, false, false, false, false, false, true, false, false, false, false, false, false, false, false, true),
      (Running, false, false, false, false, false, false, false, true, false, false, false, false, false, false, false, true),
      (Staging, false, false, false, false, false, false, false, false, true, false, false, false, false, false, false, true),
      (Starting, false, false, false, false, false, false, false, false, false, true, false, false, false, false, false, true),
      (Unreachable, false, false, false, false, false, false, false, false, false, false, true, false, false, false, false, true),
      (Gone, false, false, false, false, false, false, false, false, false, false, false, true, false, false, true, false),
      (Unknown, false, false, false, false, false, false, false, false, false, false, false, false, true, false, true, false),
      (Dropped, false, false, false, false, false, false, false, false, false, false, false, false, false, true, true, false)
    )

    forAll (conditions) { (condition: Condition, isReserved, isCreated, isError, isFailed, isFinished, isKilled, isKilling, isRunning, isStaging, isStarting, isUnreachable, isGone, isUnknown, isDropped, isTerminated, isActive) =>
      s"it's condition is $condition" should {
        val f = new Fixture

        val (instance, _) = f.instanceWith(condition, Seq(condition))

        s"${if (!isReserved) "not" else ""} be reserved" in { instance.isReserved should be(isReserved) }
        s"${if (!isCreated) "not" else ""} be created" in { instance.isCreated should be(isCreated) }
        s"${if (!isError) "not" else ""} be error" in { instance.isError should be(isError) }
        s"${if (!isFailed) "not" else ""} be failed" in { instance.isFailed should be(isFailed) }
        s"${if (!isFinished) "not" else ""} be finished" in { instance.isFinished should be(isFinished) }
        s"${if (!isKilled) "not" else ""} be killed" in { instance.isKilled should be(isKilled) }
        s"${if (!isKilling) "not" else ""} be killing" in { instance.isKilling should be(isKilling) }
        s"${if (!isRunning) "not" else ""} be running" in { instance.isRunning should be(isRunning) }
        s"${if (!isStaging) "not" else ""} be staging" in { instance.isStaging should be(isStaging) }
        s"${if (!isStarting) "not" else ""} be starting" in { instance.isStarting should be(isStarting) }
        s"${if (!isUnreachable) "not" else ""} be unreachable" in { instance.isUnreachable should be(isUnreachable) }
        s"${if (!isGone) "not" else ""} be gone" in { instance.isGone should be(isGone) }
        s"${if (!isUnknown) "not" else ""} be unknown" in { instance.isUnknown should be(isUnknown) }
        s"${if (!isDropped) "not" else ""} be dropped" in { instance.isDropped should be(isDropped) }
        s"${if (!isTerminated) "not" else ""} be terminated" in { instance.isTerminated should be(isTerminated) }
        s"${if (!isActive) "not" else ""} be active" in { instance.isActive should be(isActive) }
      }
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
      val currentTasks = tasks(conditions.map(_ => condition))
      val newTasks = tasks(conditions)
      val state = Instance.InstanceState(None, currentTasks, Timestamp.now())
      val instance = Instance(Instance.Id.forRunSpec(id), agentInfo, state, currentTasks, runSpecVersion = Timestamp.now())
      (instance, newTasks)
    }
  }
}
