package mesosphere.marathon
package core.task.update.impl.steps

import akka.actor.ActorRef
import com.google.inject.Provider
import mesosphere.UnitTest
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.InstanceUpdated
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.state.{PathId, Timestamp}

class ScaleAppUpdateStepImplTest extends UnitTest {

  // used pattern matching because of compiler checks, when additional case objects are added to Condition
  def scalingWorthy: Condition => Boolean = {
    case Condition.Reserved | Condition.Provisioned | Condition.Killing | Condition.Running |
      Condition.Staging | Condition.Starting | Condition.Unreachable => false
    case Condition.Error | Condition.Failed | Condition.Finished | Condition.Killed |
      Condition.UnreachableInactive | Condition.Gone | Condition.Dropped | Condition.Unknown => true
  }

  val allConditions = Seq(
    Condition.Reserved,
    Condition.Provisioned,
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

      val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
        .addTaskUnreachable(containerName = Some("unreachable1"))
        .getInstance()

      "send a scale request to the scheduler actor" in {
        val failedUpdate1 = f.makeFailedUpdateOp(instance, Some(Condition.Running), Condition.Failed)
        f.step.calcScaleEvent(failedUpdate1) should be (Some(ScaleRunSpec(instance.runSpecId)))
      }

      "not send a scale request again" in {
        val failedUpdate2 = f.makeFailedUpdateOp(instance, Some(Condition.Failed), Condition.Failed)
        f.step.calcScaleEvent(failedUpdate2) should be (None)
      }
    }

    notScalingWorthyConditions.foreach { newStatus =>
      s"receiving a not scaling worthy status update '$newStatus' on a previously scaling worthy condition" should {
        val f = new Fixture

        val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
          .addTaskUnreachable(containerName = Some("unreachable1"))
          .getInstance()

        val update = f.makeFailedUpdateOp(instance, Some(Condition.Failed), newStatus)

        "send no requests" in {
          f.step.calcScaleEvent(update) should be (None)
        }
      }
    }

    scalingWorthyConditions.foreach { newStatus =>
      s"receiving a scaling worthy status update '$newStatus' on a previously scaling worthy condition" should {
        val f = new Fixture

        val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
          .addTaskFailed(containerName = Some("failed1"))
          .getInstance()

        val update = f.makeFailedUpdateOp(instance, Some(Condition.Failed), newStatus)

        "send no requests" in {
          f.step.calcScaleEvent(update) should be (None)
        }
      }
    }

    scalingWorthyConditions.foreach { newStatus =>
      s"receiving a scaling worthy status update '$newStatus' on a previously non scaling worthy condition" should {
        val f = new Fixture

        val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
          .addTaskRunning(containerName = Some("running1"))
          .getInstance()

        val update = f.makeFailedUpdateOp(instance, Some(Condition.Running), newStatus)

        "send ScaleRunSpec requests" in {
          f.step.calcScaleEvent(update) should be (Some(ScaleRunSpec(instance.runSpecId)))
        }
      }
    }

    "receiving a task failed without lastState" should {
      val f = new Fixture

      val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
        .addTaskUnreachable(containerName = Some("unreachable1"))
        .getInstance()

      "send a scale request to the scheduler actor" in {
        val update = f.makeFailedUpdateOp(instance, None, Condition.Failed)
        f.step.calcScaleEvent(update) should be (Some(ScaleRunSpec(instance.runSpecId)))
      }

      "send no more requests" in {
        val update = f.makeFailedUpdateOp(instance, Some(Condition.Failed), Condition.Failed)
        f.step.calcScaleEvent(update) should be (None)
      }
    }
  }

  class Fixture {
    private[this] val schedulerActorProvider = mock[Provider[ActorRef]]
    def makeFailedUpdateOp(instance: Instance, lastCondition: Option[Condition], newCondition: Condition) =
      InstanceUpdated(instance.copy(state = instance.state.copy(condition = newCondition)), lastCondition.map(state => Instance.InstanceState(state, Timestamp.now(), Some(Timestamp.now()), Some(true), Goal.Running)), Seq.empty[MarathonEvent])

    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider)
  }
}
