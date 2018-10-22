package mesosphere.marathon
package core.task.update.impl.steps

import akka.actor.ActorRef
import com.google.inject.Provider
import mesosphere.UnitTest
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdated}
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.state.{PathId, Timestamp}

class ScaleAppUpdateStepImplTest extends UnitTest {

  val allConditions = Seq(
    Reserved,
    Created,
    Scheduled,
    Error,
    Failed,
    Finished,
    Killed,
    Killing,
    Running,
    Staging,
    Starting,
    Unreachable,
    UnreachableInactive,
    Gone,
    Dropped,
    Unknown
  )

  "ScaleAppUpdateStep" when {

    "Instances in a somewhat active state" should {
      val f = new Fixture
      val instanceId = f.instanceId
      val currentConditions = Seq(Created, Provisioned, Staging, Starting, Running, Unreachable, Killing)
      val anyOtherCondition = allConditions.filterNot(_ == UnreachableInactive)

      currentConditions.foreach { currentCondition =>
        s"trigger a scale check for a change from $currentCondition to UnreachableInactive" in {
          f.step.calcScaleEvent(instanceId, currentCondition, UnreachableInactive) shouldBe Some(ScaleRunSpec(f.runSpecId))
        }
        anyOtherCondition.foreach { condition =>
          s"not trigger a scale change for $currentCondition -> $condition" in {
            f.step.calcScaleEvent(instanceId, currentCondition, condition) shouldBe None
          }
        }
      }
    }

    "instances in UnreachableInactive" should {
      val f = new Fixture
      val instanceId = f.instanceId
      val somewhatActiveConditions = Seq(Created, Killing, Running, Staging, Starting, Unreachable)
      val ignorableConditions = allConditions.diff(somewhatActiveConditions)
      logger.info(s">>>>> $allConditions")

      somewhatActiveConditions.foreach { newCondition =>
        s"trigger a scale check for transitions to a somewhat active state ($newCondition)" in {
          f.step.calcScaleEvent(instanceId, UnreachableInactive, newCondition) shouldBe Some(ScaleRunSpec(f.runSpecId))
        }
      }
      ignorableConditions.foreach { newCondition =>
        s"not trigger a scale change for UnreachableInactive -> $newCondition" in {
          f.step.calcScaleEvent(instanceId, UnreachableInactive, newCondition) shouldBe None
        }
      }
    }

    "New instances that have no last state" should {
      val f = new Fixture

      "not trigger a scale check" in {
        val change = f.scheduledInstanceChange
        f.step.process(change).futureValue
        // we're only verifying the schedulerActor provider was never queried, hence no scale check was sent
        verify(f.schedulerActorProvider, times(0)).get()
      }
    }
  }

  class Fixture {
    val schedulerActorProvider = mock[Provider[ActorRef]]
    def scheduledInstanceChange: InstanceChange = {
      val instance = TestInstanceBuilder.newBuilder(PathId("/app")).addTaskCreated().getInstance()
      InstanceUpdated(instance, lastState = None, events = Nil)
    }

    def makeFailedUpdateOp(instance: Instance, lastCondition: Option[Condition], newCondition: Condition) =
      InstanceUpdated(instance.copy(state = instance.state.copy(condition = newCondition)), lastCondition.map(state => Instance.InstanceState(state, Timestamp.now(), Some(Timestamp.now()), Some(true), Goal.Running)), Seq.empty[MarathonEvent])

    // TODO: there is no `scheduled` helper yet but created is basically the same
    val runSpecId: PathId = PathId("/app")
    val instanceId: Instance.Id = Instance.Id.forRunSpec(runSpecId)

    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider)
  }
}
