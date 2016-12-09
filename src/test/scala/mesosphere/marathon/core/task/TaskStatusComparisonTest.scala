package mesosphere.marathon
package core.task

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.Timestamp
import org.scalatest.prop.TableDrivenPropertyChecks

class TaskStatusComparisonTest extends UnitTest with TableDrivenPropertyChecks {

  "A task" when {

    // format: OFF
    // Conditions and their expected flag values. e.g. if status is Condition.Reserved isReserved is expected to be true
    // and isCreated to be false.
    val conditions = Table (
      ("condition",                   "isReserved", "isCreated", "isError", "isFailed", "isFinished", "isKilled", "isKilling", "isRunning", "isStaging", "isStarting", "isUnreachable", "isUnreachableInactive", "isGone", "isUnknown", "isDropped", "isActive", "isTerminal"),
      (Condition.Reserved,            true,         false,       false,     false,      false,        false,      false,       false,       false,       false,        false,           false,                   false,    false,       false,       false,      false       ),
      (Condition.Created,             false,        true,        false,     false,      false,        false,      false,       false,       false,       false,        false,           false,                   false,    false,       false,       true,       false       ),
      (Condition.Error,               false,        false,       true,      false,      false,        false,      false,       false,       false,       false,        false,           false,                   false,    false,       false,       false,      true        ),
      (Condition.Failed,              false,        false,       false,     true,       false,        false,      false,       false,       false,       false,        false,           false,                   false,    false,       false,       false,      true        ),
      (Condition.Finished,            false,        false,       false,     false,      true,         false,      false,       false,       false,       false,        false,           false,                   false,    false,       false,       false,      true        ),
      (Condition.Killed,              false,        false,       false,     false,      false,        true,       false,       false,       false,       false,        false,           false,                   false,    false,       false,       false,      true        ),
      (Condition.Killing,             false,        false,       false,     false,      false,        false,      true,        false,       false,       false,        false,           false,                   false,    false,       false,       true,       false       ),
      (Condition.Running,             false,        false,       false,     false,      false,        false,      false,       true,        false,       false,        false,           false,                   false,    false,       false,       true,       false       ),
      (Condition.Staging,             false,        false,       false,     false,      false,        false,      false,       false,       true,        false,        false,           false,                   false,    false,       false,       true,       false       ),
      (Condition.Starting,            false,        false,       false,     false,      false,        false,      false,       false,       false,       true,         false,           false,                   false,    false,       false,       true,       false       ),
      (Condition.Unreachable,         false,        false,       false,     false,      false,        false,      false,       false,       false,       false,        true,            false,                   false,    false,       false,       true,       false       ),
      (Condition.UnreachableInactive, false,        false,       false,     false,      false,        false,      false,       false,       false,       false,        false,           true,                    false,    false,       false,       false,      false       ),
      (Condition.Gone,                false,        false,       false,     false,      false,        false,      false,       false,       false,       false,        false,           false,                   true,     false,       false,       false,      true        ),
      (Condition.Unknown,             false,        false,       false,     false,      false,        false,      false,       false,       false,       false,        false,           false,                   false,    true,        false,       false,      true        ),
      (Condition.Dropped,             false,        false,       false,     false,      false,        false,      false,       false,       false,       false,        false,           false,                   false,    false,       true,        false,      true        )
    )
    // format: ON

    forAll (conditions) { (condition: Condition, isReserved, isCreated, isError, isFailed, isFinished, isKilled, isKilling, isRunning, isStaging, isStarting, isUnreachable, isUnreachableInactive, isGone, isUnknown, isDropped, isActive, isTerminal) =>
      s"it's condition is $condition" should {

        val status = Task.Status(Timestamp.now, None, None, condition, NetworkInfo.empty)
        val task = mock[Task]
        task.status returns status

        import Task._

        s"${if (!isReserved) "not" else ""} be reserved" in { task.isReserved should be(isReserved) }
        s"${if (!isCreated) "not" else ""} be created" in { task.isCreated should be(isCreated) }
        s"${if (!isError) "not" else ""} be error" in { task.isError should be(isError) }
        s"${if (!isFailed) "not" else ""} be failed" in { task.isFailed should be(isFailed) }
        s"${if (!isFinished) "not" else ""} be finished" in { task.isFinished should be(isFinished) }
        s"${if (!isKilled) "not" else ""} be killed" in { task.isKilled should be(isKilled) }
        s"${if (!isKilling) "not" else ""} be killing" in { task.isKilling should be(isKilling) }
        s"${if (!isRunning) "not" else ""} be running" in { task.isRunning should be(isRunning) }
        s"${if (!isStaging) "not" else ""} be staging" in { task.isStaging should be(isStaging) }
        s"${if (!isStarting) "not" else ""} be starting" in { task.isStarting should be(isStarting) }
        s"${if (!isUnreachable) "not" else ""} be unreachable" in { task.isUnreachable should be(isUnreachable) }
        s"${if (!isUnreachableInactive) "not" else ""} be unreachable inactive" in { task.isUnreachableInactive should be(isUnreachableInactive) }
        s"${if (!isGone) "not" else ""} be gone" in { task.isGone should be(isGone) }
        s"${if (!isUnknown) "not" else ""} be unknown" in { task.isUnknown should be(isUnknown) }
        s"${if (!isDropped) "not" else ""} be dropped" in { task.isDropped should be(isDropped) }
        s"${if (!isActive) "not" else ""} be active" in { task.isActive should be(isActive) }
        s"${if (!isTerminal) "not" else ""} be terminated" in { task.isTerminal should be(isTerminal) }
      }
    }
  }
}
