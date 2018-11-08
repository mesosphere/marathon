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

    "Instances transitioning to or from UnreachableInactive" should {
      val f = new Fixture
      val instanceId = f.instanceId
      val notUnreachableInactive = allConditions.filterNot(_ == UnreachableInactive)

      notUnreachableInactive.foreach { currentCondition =>
        s"trigger a scale check for a change from $currentCondition to UnreachableInactive" in {
          f.step.calcScaleEvent(instanceId, currentCondition, UnreachableInactive) shouldBe Some(ScaleRunSpec(f.runSpecId))
        }
      }

      notUnreachableInactive.foreach { currentCondition =>
        s"trigger a scale check for a change from UnreachableInactive to $currentCondition" in {
          f.step.calcScaleEvent(instanceId, UnreachableInactive, currentCondition) shouldBe Some(ScaleRunSpec(f.runSpecId))
        }
      }

      notUnreachableInactive.foreach { condition =>
        val allOthers = notUnreachableInactive.filterNot(_ == condition)
        allOthers.foreach { otherCondition =>
          s"not trigger a scale change for $condition -> $otherCondition" in {
            f.step.calcScaleEvent(instanceId, condition, otherCondition) shouldBe None
          }
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
      val instance = TestInstanceBuilder.newBuilder(PathId("/app")).addTaskProvisioned(None).getInstance()
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
