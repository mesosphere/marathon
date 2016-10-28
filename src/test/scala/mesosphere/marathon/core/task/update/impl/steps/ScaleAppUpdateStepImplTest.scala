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

    val nonTerminalStates = Seq(Condition.Unreachable, Condition.Reserved)
    nonTerminalStates.foreach { newStatus =>
      s"receiving a non-terminal status update $newStatus" should {
        val f = new Fixture

        val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
          .addTaskUnreachable(containerName = Some("unreachable1"))
          .getInstance()

        val update = f.makeFailedUpdateOp(instance, Some(Condition.Running), newStatus)
        f.step.process(update)

        "send no requests" in {
          f.schedulerActor.expectNoMsg()
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
      InstanceUpdated(instance.copy(state = instance.state.copy(condition = newCondition)), lastCondition.map(state => Instance.InstanceState(state, Timestamp.now(), Some(true))), Seq.empty[MarathonEvent])

    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider)
  }
}
