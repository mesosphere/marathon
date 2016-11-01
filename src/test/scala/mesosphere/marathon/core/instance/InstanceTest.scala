package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp

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

          val status = Instance.InstanceState(Some(instance.state), tasks, f.clock.now())

          s"change to $to" in {
            status.condition should be(to)
          }
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
