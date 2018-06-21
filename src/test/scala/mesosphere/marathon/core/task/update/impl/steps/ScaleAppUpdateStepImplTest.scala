package mesosphere.marathon
package core.task.update.impl.steps

import java.time.Clock

import akka.actor.ActorRef
import com.google.inject.Provider
import mesosphere.UnitTest
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdated
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.state.{PathId, Timestamp}

class ScaleAppUpdateStepImplTest extends UnitTest {

  // used pattern matching because of compiler checks, when additional case objects are added to Condition
  def scalingWorthy: Condition => Boolean = {
    case Condition.Reserved | Condition.Created | Condition.Killing | Condition.Running |
      Condition.Staging | Condition.Starting | Condition.Unreachable => false
    case Condition.Error | Condition.Failed | Condition.Finished | Condition.Killed |
      Condition.UnreachableInactive | Condition.Gone | Condition.Dropped | Condition.Unknown => true
  }

  val allConditions = Seq(
    Condition.Reserved,
    Condition.Created,
    Condition.Error,
    Condition.Failed,
    Condition.Finished,
    Condition.Killed,
    Condition.Killing,
    Condition.Running,
    Condition.Staging,
    Condition.Starting,
    Condition.Unreachable,
    Condition.UnreachableInactive,
    Condition.Gone,
    Condition.Dropped,
    Condition.Unknown
  )

  val scalingWorthyConditions = allConditions.filter(scalingWorthy)
  val notScalingWorthyConditions = allConditions.filterNot(scalingWorthy)

  "ScaleAppUpdateStep" when {
    "receiving multiple failed tasks" should {
      val f = new Fixture

      val runSpecId = PathId("/app")

      "send a scale request to the scheduler actor" in {
        val failedUpdate1 = f.makeFailedUpdateOp(runSpecId, Some(TestInstanceBuilder.newBuilder(runSpecId).addTaskRunning().instance), Condition.Failed)
        f.step.calcScaleEvent(failedUpdate1) should be (Some(ScaleRunSpec(runSpecId)))
      }

      "not send a scale request again" in {
        val failedUpdate2 = f.makeFailedUpdateOp(runSpecId, Some(TestInstanceBuilder.newBuilder(runSpecId).addTaskFailed().instance), Condition.Failed)
        f.step.calcScaleEvent(failedUpdate2) should be (None)
      }
    }

    notScalingWorthyConditions.foreach { newStatus =>
      s"receiving a not scaling worthy status update '$newStatus' on a previously scaling worthy condition" should {
        val f = new Fixture

        val runSpecId = PathId("/app")

        val update = f.makeFailedUpdateOp(runSpecId, Some(TestInstanceBuilder.newBuilder(runSpecId).addTaskFailed().instance), newStatus)

        "send no requests" in {
          f.step.calcScaleEvent(update) should be (None)
        }
      }
    }

    scalingWorthyConditions.foreach { newStatus =>
      s"receiving a scaling worthy status update '$newStatus' on a previously scaling worthy condition" should {
        val f = new Fixture

        val runSpecId = PathId("/app")

        val update = f.makeFailedUpdateOp(runSpecId, Some(TestInstanceBuilder.newBuilder(runSpecId).addTaskFailed().instance), newStatus)

        "send no requests" in {
          f.step.calcScaleEvent(update) should be (None)
        }
      }
    }

    scalingWorthyConditions.foreach { newStatus =>
      s"receiving a scaling worthy status update '$newStatus' on a previously non scaling worthy condition" should {
        val f = new Fixture
        val runSpecId = PathId("/app")

        val update = f.makeFailedUpdateOp(runSpecId, Some(TestInstanceBuilder.newBuilder(runSpecId).addTaskRunning().instance), newStatus)

        "send ScaleRunSpec requests" in {
          f.step.calcScaleEvent(update) should be (Some(ScaleRunSpec(runSpecId)))
        }
      }
    }

    "receiving a task failed without lastState" should {
      val f = new Fixture

      val runSpecId = PathId("/app")

      "send a scale request to the scheduler actor" in {
        val update = f.makeFailedUpdateOp(runSpecId, None, Condition.Failed)
        f.step.calcScaleEvent(update) should be (Some(ScaleRunSpec(runSpecId)))
      }

      "send no more requests" in {
        val update = f.makeFailedUpdateOp(runSpecId, Some(TestInstanceBuilder.newBuilder(runSpecId).addTaskFailed().instance), Condition.Failed)
        f.step.calcScaleEvent(update) should be (None)
      }
    }
  }

  class Fixture {
    private[this] val schedulerActorProvider = mock[Provider[ActorRef]]
    def makeFailedUpdateOp(runSpecId: PathId, lastState: Option[Instance], newCondition: Condition) = {
      val newInstance = TestInstanceBuilder.newBuilder(runSpecId).addTaskWithBuilder().taskWithCondition(newCondition).instanceBuilder.instance
      InstanceUpdated(newInstance, lastState, Seq.empty[MarathonEvent])
    }
    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider, Clock.systemUTC())
  }
}
