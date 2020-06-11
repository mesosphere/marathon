package mesosphere.marathon
package core.task.update.impl.steps

import com.google.inject.Provider
import mesosphere.UnitTest
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import org.scalatest.prop.TableDrivenPropertyChecks

class NotifyRateLimiterStepImplTest extends UnitTest with TableDrivenPropertyChecks {

  "NotifyRateLimiterStepImpl" when {
    val updateCombinations = Table(
      ("test case", "expected action", "instanceChange"),
      ("killed(draining)", ResetDelay, TaskStatusUpdateTestHelper.killed(draining = true).wrapped),
      ("goneByOperator(draining)", ResetDelay, TaskStatusUpdateTestHelper.goneByOperator(draining = true).wrapped),
      ("dropped", AddDelay, TaskStatusUpdateTestHelper.dropped().wrapped),
      ("error", AddDelay, TaskStatusUpdateTestHelper.error().wrapped),
      ("failed", AddDelay, TaskStatusUpdateTestHelper.failed().wrapped),
      ("gone", AddDelay, TaskStatusUpdateTestHelper.gone().wrapped),
      ("finished", AddDelay, TaskStatusUpdateTestHelper.finished().wrapped),
      ("starting", AdvanceDelay, TaskStatusUpdateTestHelper.starting().wrapped),
      ("running", AdvanceDelay, TaskStatusUpdateTestHelper.running().wrapped),
      ("unknown", Noop, TaskStatusUpdateTestHelper.unknown().wrapped),
      ("killed", Noop, TaskStatusUpdateTestHelper.killed().wrapped),
      ("unreachable", Noop, TaskStatusUpdateTestHelper.unreachable().wrapped)
    )

    "processing update" when {
      forAll(updateCombinations) { (testCase: String, expectedAction: ExpectedAction, instanceChange: InstanceChange) =>
        s"$testCase should $expectedAction" in {
          val f = new Fixture
          f.step.process(instanceChange).futureValue
          expectedAction match {
            case ResetDelay => verify(f.launchQueue).resetDelay(instanceChange.instance.runSpec)
            case AddDelay => verify(f.launchQueue).addDelay(instanceChange.instance.runSpec)
            case AdvanceDelay => verify(f.launchQueue).advanceDelay(instanceChange.instance.runSpec)
            case Noop => noMoreInteractions(f.launchQueue)
          }
        }
      }
    }
  }

  sealed trait ExpectedAction extends Product with Serializable
  case object ResetDelay extends ExpectedAction
  case object AddDelay extends ExpectedAction
  case object AdvanceDelay extends ExpectedAction
  case object Noop extends ExpectedAction

  class Fixture {
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    private val launchQueueProvider = new Provider[LaunchQueue] {
      override def get(): LaunchQueue = launchQueue
    }

    val step = new NotifyRateLimiterStepImpl(launchQueueProvider)
  }

}
