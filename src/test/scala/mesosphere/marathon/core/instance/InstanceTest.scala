package mesosphere.marathon.core.instance

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

class InstanceTest extends FunSuite with Matchers with GivenWhenThen {

  test("State changes are computed correctly") {
    testStateChange(from = Created, to = Created, Created, Created, Created)
    testStateChange(from = Created, to = Staging, Created, Created, Staging)
    testStateChange(from = Staging, to = Staging, Running, Staging, Running)
    testStateChange(from = Running, to = Running, Running, Finished, Running)
    testStateChange(from = Running, to = Failed, Staging, Starting, Running, Killing, Finished, Failed)
    testStateChange(from = Running, to = Killing, Staging, Starting, Running, Killing, Finished)
    testStateChange(from = Running, to = Error, Staging, Starting, Running, Killing, Finished, Failed, Error)
    testStateChange(from = Staging, to = Staging, Staging)
    testStateChange(from = Running, to = Gone, Gone, Running, Running)
    testStateChange(from = Killing, to = Killed, Killed, Killed, Killed)
    testStateChange(from = Running, to = Killing, Running, Killing, Killed)
    testStateChange(from = Running, to = Gone, Running, Gone, Dropped)
    testStateChange(from = Running, to = Dropped, Unreachable, Dropped)

  }

  def testStateChange(from: Condition, to: Condition, withTasks: Condition*): Unit = {
    Given(s"An instance in status $from with ${withTasks.size} Tasks in status $from")
    val (instance, tasks) = instanceWith(from, withTasks)

    When(s"The tasks become ${withTasks.mkString(", ")}")
    val status = Instance.newInstanceState(Some(instance.state), tasks, clock.now())

    Then(s"The status should be $to")
    status.condition should be(to)
  }

  val id = "/test".toPath
  val clock = ConstantClock()

  def instanceWith(condition: Condition, conditions: Seq[Condition]): (Instance, Map[Task.Id, Task]) = {
    def tasks(statuses: Seq[Condition]): Map[Task.Id, Task] = {

      statuses
        .map { status =>
          val task = TestTaskBuilder.Helper.minimalTask(Task.Id.forRunSpec(id), Timestamp.now(), None, status)
          task.taskId -> task
        }(collection.breakOut)
    }
    val state = InstanceState(condition, Timestamp.now(), None)
    val currentTasks = tasks(conditions.map(_ => condition))
    val newTasks = tasks(conditions)
    val instance = Instance(Instance.Id.forRunSpec(id), Instance.AgentInfo("", None, Nil), state, currentTasks, runSpecVersion = Timestamp.now())
    (instance, newTasks)
  }
}
