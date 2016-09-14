package mesosphere.marathon.core.instance

import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.InstanceStatus._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import org.scalatest.{ GivenWhenThen, FunSuite, Matchers }

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

  def testStateChange(from: InstanceStatus, to: InstanceStatus, withTasks: InstanceStatus*): Unit = {
    Given(s"An instance in status $from with ${withTasks.size} Tasks in status $from")
    val (instance, tasks) = instanceWith(from, withTasks)

    When(s"The tasks become ${withTasks.mkString(", ")}")
    val status = instance.newInstanceState(tasks)

    Then(s"The status should be $to")
    status.status should be(to)
  }

  val id = "/test".toPath

  def instanceWith(status: InstanceStatus, taskStates: Seq[InstanceStatus]): (Instance, Map[Task.Id, Task]) = {
    def tasks(statuses: Seq[InstanceStatus]): Map[Task.Id, Task] = {
      import mesosphere.marathon.MarathonTestHelper._
      statuses
        .map { mininimalTask(Task.Id.forRunSpec(id).toString, Timestamp.now(), None, _) }
        .map(task => task.taskId -> task)
        .toMap
    }
    val state = InstanceState(status, Timestamp.now(), Timestamp.now(), None)
    val currentTasks = tasks(taskStates.map(_ => status))
    val newTasks = tasks(taskStates)
    val instance = Instance(Instance.Id.forRunSpec(id), Instance.AgentInfo("", None, Iterable.empty), state, currentTasks)
    (instance, newTasks)
  }
}
