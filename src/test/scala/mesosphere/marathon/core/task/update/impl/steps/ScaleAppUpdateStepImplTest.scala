package mesosphere.marathon
package core.task.update.impl.steps

import akka.actor.ActorRef
import com.google.inject.Provider
import mesosphere.UnitTest
import mesosphere.marathon.MarathonSchedulerActor.{DecommissionInstance, StartInstance}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdated
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}

class ScaleAppUpdateStepImplTest extends UnitTest {

  "ScaleAppUpdateStepImpl" should {
    "start new instance when an existing one becomes UnreachableInactive" in new Fixture {
      val instance = unreachable()

      val update = instanceUpdate(instance, Condition.UnreachableInactive)

      step.maybeSchedulerCommand(update) shouldBe Some(StartInstance(instance.runSpec))
    }

    "do not do anything when an existing one becomes UnreachableInactive *again*" in new Fixture {
      val instance = unreachableInactive()

      val update = instanceUpdate(instance, Condition.UnreachableInactive)

      step.maybeSchedulerCommand(update) shouldBe None
    }

    "decommission an instance when an existing one becomes reachable again" in new Fixture {
      val instance = unreachableInactive()

      val update = instanceUpdate(instance, Condition.Running)

      step.maybeSchedulerCommand(update) shouldBe Some(DecommissionInstance(instance.runSpec))
    }

    val ignoredConditions = Condition.all.filter(_ != Condition.UnreachableInactive)

    ignoredConditions.foreach { condition =>
      s"Ignore a '$condition' update if it is not UnreachableInactive" in new Fixture {
        ignoredConditions.foreach { previous =>
          val instance = conditioned(condition = previous)
          val update = instanceUpdate(instance, condition)

          step.maybeSchedulerCommand(update) shouldBe None
        }
      }
    }

  }

  class Fixture {
    private[this] val schedulerActorProvider = mock[Provider[ActorRef]]

    def scheduled(): Instance = Instance
      .scheduled(AppDefinition(id = PathId("/app")))

    /**
      * Strictly speaking this is not a valid instance that we're building here e.g. a [[Condition.Running]] should
      * have tasks. But this is enough for the purposes of this test.
      *
      * @param instance
      * @param condition
      * @return
      */
    def conditioned(instance: Instance = scheduled(), condition: Condition) =
      instance
        .copy(state = InstanceState(
          condition = condition,
          since = Timestamp.now(),
          activeSince = None,
          healthy = None,
          goal = Goal.Running
        ))

    def unreachable(): Instance = conditioned(condition = Condition.Unreachable)
    def unreachableInactive(): Instance = conditioned(condition = Condition.UnreachableInactive)

    def instanceUpdate(instance: Instance, newCondition: Condition) =
      InstanceUpdated(
        instance.copy(state = instance.state.copy(condition = newCondition)),
        Some(instance.state),
        Seq.empty[MarathonEvent])

    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider)
  }
}
