package mesosphere.marathon
package core.task.jobs

import java.time.Clock

import akka.actor.{ActorRef, PoisonPill, Terminated}
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.jobs.impl.{ExpungeOverdueLostTasksActor, ExpungeOverdueLostTasksActorLogic}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state._
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class ExpungeOverdueLostTasksActorTest extends AkkaUnitTest with TableDrivenPropertyChecks {

  class Fixture {
    val clock = new SettableClock()
    val config = MarathonTestHelper.defaultConfig(maxInstancesPerOffer = 10)
    val instanceTracker: InstanceTracker = mock[InstanceTracker]
    val fiveTen = UnreachableEnabled(inactiveAfter = 5.minutes, expungeAfter = 10.minutes)
  }

  def withActor(testCode: (Fixture, ActorRef) => Any): Unit = {

    val f = new Fixture
    val checkActor = system.actorOf(ExpungeOverdueLostTasksActor.props(f.clock, f.config, f.instanceTracker))

    try {
      testCode(f, checkActor)
    } finally {
      checkActor ! PoisonPill
      val probe = TestProbe()
      probe.watch(checkActor)
      val terminated = probe.expectMsgAnyClassOf(classOf[Terminated])
      assert(terminated.actor == checkActor)
    }
  }

  "The expunge overdue tasks business logic's filtering methods" in {

    val f = new Fixture

    val businessLogic = new ExpungeOverdueLostTasksActorLogic {
      override val config: TaskJobsConfig = MarathonTestHelper.defaultConfig(maxInstancesPerOffer = 10)
      override val clock: Clock = new SettableClock()
      override val instanceTracker: InstanceTracker = mock[InstanceTracker]
    }

    // format: OFF
    // Different task configuration with startedAt, status since and condition values. Expunge indicates whether an
    // expunge is expected or not.
    import f.fiveTen
    val disabled = UnreachableDisabled
    val taskCases = Table(
      ("name",             "startedAt",    "since",                                       "unreachableStrategy", "condition",                   "expunge"),
      ("running",          Timestamp.zero, Timestamp.zero,                                fiveTen,               Condition.Running,             false    ),
      ("expired inactive", Timestamp.zero, f.clock.now - fiveTen.expungeAfter - 1.minute, fiveTen,               Condition.UnreachableInactive, true     ),
      ("unreachable",      Timestamp.zero, f.clock.now - 5.minutes,                       fiveTen,               Condition.Unreachable,         false    ),
      ("expired disabled", Timestamp.zero, f.clock.now - 365.days,                        disabled,              Condition.Unreachable,         false    )
    )
    // format: ON

    forAll(taskCases) { (name: String, startedAt: Timestamp, since: Timestamp, unreachableStrategy: UnreachableStrategy, condition: Condition, expunge: Boolean) =>
      When(s"filtering $name task since $since")
      val instance: Instance = (condition match {
        case Condition.Unreachable =>
          TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable")).addTaskUnreachable(since = since)
        case Condition.UnreachableInactive =>
          TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable")).addTaskUnreachableInactive(since = since)
        case _ =>
          TestInstanceBuilder.newBuilder(AbsolutePathId("/running")).addTaskRunning(startedAt = startedAt)
      }).withUnreachableStrategy(unreachableStrategy).getInstance()
      val instances = InstancesBySpec.forInstances(Seq(instance)).instancesMap

      val filterForExpunge = businessLogic.filterUnreachableForExpunge(instances, f.clock.now()).map(identity)

      Then(s"${if (!expunge) "not " else ""}select it for expunge")
      filterForExpunge.nonEmpty should be(expunge)
    }

    When("filtering two running tasks")
    val running1 = TestInstanceBuilder.newBuilder(AbsolutePathId("/running1")).addTaskRunning(startedAt = Timestamp.zero)
      .withUnreachableStrategy(f.fiveTen)
      .getInstance()
    val running2 = TestInstanceBuilder.newBuilder(AbsolutePathId("/running2")).addTaskRunning(startedAt = Timestamp.zero)
      .withUnreachableStrategy(f.fiveTen)
      .getInstance()
    val instances = InstancesBySpec.forInstances(Seq(running1, running2)).instancesMap

    val filtered = businessLogic.filterUnreachableForExpunge(instances, f.clock.now()).map(identity)

    Then("return an empty collection")
    filtered.isEmpty should be(true)

    When("filtering two expired inactive Unreachable tasks")
    val inactive1 = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable1")).addTaskUnreachableInactive(since = Timestamp.zero)
      .withUnreachableStrategy(f.fiveTen)
      .getInstance()
    val inactive2 = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable1")).addTaskUnreachableInactive(since = Timestamp.zero)
      .withUnreachableStrategy(f.fiveTen)
      .getInstance()

    val instances2 = InstancesBySpec.forInstances(Seq(inactive1, inactive2)).instancesMap

    val filtered2 = businessLogic.filterUnreachableForExpunge(instances2, f.clock.now()).map(identity)

    Then("return the expired Unreachable tasks")
    filtered2 should be(Iterable(inactive1, inactive2))
  }

  "The ExpungeOverdueLostTaskActor" when {
    "checking two running tasks" in withActor { (f: Fixture, checkActor: ActorRef) =>
      val running1 = TestInstanceBuilder.newBuilder(AbsolutePathId("/running1")).addTaskRunning(startedAt = Timestamp.zero)
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()
      val running2 = TestInstanceBuilder.newBuilder(AbsolutePathId("/running2")).addTaskRunning(startedAt = Timestamp.zero)
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()

      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(Seq(running1, running2)))

      Then("issue no expunge")
      noMoreInteractions(f.instanceTracker)
    }

    "checking one inactive Unreachable and one running task" in withActor { (f: Fixture, checkActor: ActorRef) =>
      val running = TestInstanceBuilder.newBuilder(AbsolutePathId("/running")).addTaskRunning(startedAt = Timestamp.zero)
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()
      val unreachable = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable")).addTaskUnreachableInactive(since = Timestamp.zero)
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()

      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(Seq(running, unreachable)))

      val testProbe = TestProbe()
      testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
      testProbe.receiveOne(3.seconds)

      Then("issue one expunge")
      verify(f.instanceTracker, once).forceExpunge(unreachable.instanceId)
    }

    "checking two inactive Unreachable tasks and one is overdue" in withActor { (f: Fixture, checkActor: ActorRef) =>
      val unreachable1 = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable1")).addTaskUnreachableInactive(since = Timestamp.zero)
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()
      val unreachable2 = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable2")).addTaskUnreachableInactive(since = f.clock.now())
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()

      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(Seq(unreachable1, unreachable2)))

      val testProbe = TestProbe()
      testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
      testProbe.receiveOne(3.seconds)

      Then("issue one expunge")
      verify(f.instanceTracker, once).forceExpunge(unreachable1.instanceId)
    }

    "checking two lost task and one is overdue" in withActor { (f: Fixture, checkActor: ActorRef) =>
      // Note that both won't have unreachable time set.
      val unreachable1 = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable1")).addTaskLost(since = Timestamp.zero)
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()
      val unreachable2 = TestInstanceBuilder.newBuilder(AbsolutePathId("/unreachable2")).addTaskLost(since = f.clock.now())
        .withUnreachableStrategy(f.fiveTen)
        .getInstance()

      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(Seq(unreachable1, unreachable2)))

      val testProbe = TestProbe()

      // Trigger UnreachableInactive mark
      testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
      testProbe.receiveOne(3.seconds)

      Then("ensure backwards compatibility and issue one expunge")
      val (taskId, task) = unreachable1.tasksMap.head
      verify(f.instanceTracker, once).forceExpunge(unreachable1.instanceId)
    }
  }
}
