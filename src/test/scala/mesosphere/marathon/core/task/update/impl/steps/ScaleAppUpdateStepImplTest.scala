package mesosphere.marathon.core.task.update.impl.steps

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.google.inject.Provider
import mesosphere.AkkaUnitTest
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.InstanceUpdated
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.state.{ PathId, Timestamp }

class ScaleAppUpdateStepImplTest extends AkkaUnitTest {

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

      val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
        .addTaskUnreachable(containerName = Some("unreachable1"))
        .getInstance()

      val failedUpdate1 = f.makeFailedUpdateOp(instance, Some(Condition.Running), Condition.Failed)
      f.step.process(failedUpdate1)

      "send a scale request to the scheduler actor" in {
        val answer = f.schedulerActor.expectMsgType[ScaleRunSpec]
        answer.runSpecId should be(instance.instanceId.runSpecId)
      }
      "not send a scale request again" in {
        val failedUpdate2 = f.makeFailedUpdateOp(instance, Some(Condition.Failed), Condition.Failed)
        f.step.process(failedUpdate2)
        f.schedulerActor.expectNoMsg()
      }
    }

    notScalingWorthyConditions.foreach { newStatus =>
      s"receiving a not scaling worthy status update '$newStatus' on a previously scaling worthy condition" should {
        val f = new Fixture

        val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
          .addTaskUnreachable(containerName = Some("unreachable1"))
          .getInstance()

        val update = f.makeFailedUpdateOp(instance, Some(Condition.Failed), newStatus)
        f.step.process(update)

        "send no requests" in {
          f.schedulerActor.expectNoMsg()
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
        f.step.process(update)

        "send no requests" in {
          f.schedulerActor.expectNoMsg()
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
        f.step.process(update)

        "send ScaleRunSpec requests" in {
          f.schedulerActor.expectMsgType[ScaleRunSpec]
        }
      }
    }

    "receiving a task failed without lateState" should {
      val f = new Fixture

      val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
        .addTaskUnreachable(containerName = Some("unreachable1"))
        .getInstance()

      val failedUpdate1 = f.makeFailedUpdateOp(instance, None, Condition.Failed)
      f.step.process(failedUpdate1)

      "send a scale request to the scheduler actor" in {
        val answer = f.schedulerActor.expectMsgType[ScaleRunSpec]
        answer.runSpecId should be(instance.instanceId.runSpecId)
      }

      "send no more requests" in {
        f.schedulerActor.expectNoMsg()
      }
    }
  }

  class Fixture {
    val schedulerActor: TestProbe = TestProbe()
    val schedulerActorProvider = new Provider[ActorRef] {
      override def get(): ActorRef = schedulerActor.ref
    }

    def makeFailedUpdateOp(instance: Instance, lastCondition: Option[Condition], newCondition: Condition) =
      InstanceUpdated(instance.copy(state = instance.state.copy(condition = newCondition)), lastCondition.map(state => Instance.InstanceState(state, Timestamp.now(), Some(Timestamp.now()), Some(true))), Seq.empty[MarathonEvent])

    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider)
  }
}
