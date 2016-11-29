package mesosphere.marathon
package core.task.jobs

import akka.actor.{ ActorRef, PoisonPill, Terminated }
import akka.event.LoggingAdapter
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.jobs.impl.{ ExpungeOverdueLostTasksActor, ExpungeOverdueLostTasksActorLogic }
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Timestamp, UnreachableStrategy }
import mesosphere.marathon.test.MarathonTestHelper
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

class ExpungeOverdueLostTasksActorTest extends AkkaUnitTest with TableDrivenPropertyChecks {

  class Fixture {
    val clock = ConstantClock()
    val config = MarathonTestHelper.defaultConfig(maxTasksPerOffer = 10)
    val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
    val taskTracker: InstanceTracker = mock[InstanceTracker]
    val strategy = UnreachableStrategy(5.minutes, 10.minutes)
  }

  def withActor(testCode: (Fixture, ActorRef) => Any): Unit = {

    val f = new Fixture
    val checkActor = system.actorOf(ExpungeOverdueLostTasksActor.props(f.clock, f.config, f.taskTracker, f.stateOpProcessor))

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

  "The expunge overdue tasks business logic's filtering methods" when {

    val f = new Fixture

    val businessLogic = new ExpungeOverdueLostTasksActorLogic {
      override val config: TaskJobsConfig = MarathonTestHelper.defaultConfig(maxTasksPerOffer = 10)
      override val clock: Clock = ConstantClock()
      override val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
      override def log = mock[LoggingAdapter]
    }

    // format: OFF
    // Different task configuration with startedAt, status since and condition values. Expunge indicates whether an
    // expunge is expected or not.
    val taskCases = Table(
      ("name",             "startedAt",    "since",                                                 "condition",                   "expunge"),
      ("running",          Timestamp.zero, Timestamp.zero,                                          Condition.Running,             false    ),
      ("expired inactive", Timestamp.zero, f.clock.now - f.strategy.timeUntilExpunge - 1.minute, Condition.UnreachableInactive, true     ),
      ("unreachable",      Timestamp.zero, f.clock.now - f.strategy.timeUntilInactive,           Condition.Unreachable,         false    )
    )
    // format: ON

    forAll(taskCases) { (name: String, startedAt: Timestamp, since: Timestamp, condition: Condition, expunge: Boolean) =>
      s"filtering $name task since $since" should {
        val instance: Instance = (condition match {
          case Condition.Unreachable => TestInstanceBuilder.newBuilder("/unreachable".toPath).addTaskUnreachable(since = since).getInstance()
          case Condition.UnreachableInactive => TestInstanceBuilder.newBuilder("/unreachable".toPath).addTaskUnreachableInactive(since = since).getInstance()
          case _ => TestInstanceBuilder.newBuilder("/running".toPath).addTaskRunning(startedAt = startedAt).getInstance()
        }).copy(unreachableStrategy = f.strategy)
        val instances = InstancesBySpec.forInstances(instance).instancesMap

        val filterForExpunge = businessLogic.filterOverdueUnreachableInactive(instances, f.clock.now()).map(identity)

        s"${if (!expunge) "not" else ""} select it for expunge" in { filterForExpunge.nonEmpty should be(expunge) }
      }
    }

    "filtering two running tasks" should {
      val running1 = TestInstanceBuilder.newBuilder("/running1".toPath).addTaskRunning(startedAt = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val running2 = TestInstanceBuilder.newBuilder("/running2".toPath).addTaskRunning(startedAt = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val instances = InstancesBySpec.forInstances(running1, running2).instancesMap

      val filtered = businessLogic.filterOverdueUnreachableInactive(instances, f.clock.now()).map(identity)

      "return an empty collection" in { filtered.isEmpty should be(true) }
    }

    "filtering two expired inactive Unreachable tasks" should {
      val inactive1 = TestInstanceBuilder.newBuilder("/unreachable1".toPath).addTaskUnreachableInactive(since = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val inactive2 = TestInstanceBuilder.newBuilder("/unreachable1".toPath).addTaskUnreachableInactive(since = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)

      val instances = InstancesBySpec.forInstances(inactive1, inactive2).instancesMap

      val filtered = businessLogic.filterOverdueUnreachableInactive(instances, f.clock.now()).map(identity)

      "return the expired Unreachable tasks" in { filtered should be(Iterable(inactive1, inactive2)) }
    }
  }

  "The ExpungeOverdueLostTaskActor" when {
    "checking two running tasks" should withActor { (f: Fixture, checkActor: ActorRef) =>
      val running1 = TestInstanceBuilder.newBuilder("/running1".toPath).addTaskRunning(startedAt = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val running2 = TestInstanceBuilder.newBuilder("/running2".toPath).addTaskRunning(startedAt = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)

      f.taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(running1, running2))

      "issue no expunge" in {
        noMoreInteractions(f.stateOpProcessor)
      }
    }

    "checking one inactive Unreachable and one running task" should withActor { (f: Fixture, checkActor: ActorRef) =>
      val running = TestInstanceBuilder.newBuilder("/running".toPath).addTaskRunning(startedAt = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val unreachable = TestInstanceBuilder.newBuilder("/unreachable".toPath).addTaskUnreachableInactive(since = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)

      f.taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(running, unreachable))

      val testProbe = TestProbe()
      testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
      testProbe.receiveOne(3.seconds)

      "issue one expunge" in {
        verify(f.stateOpProcessor, once).process(InstanceUpdateOperation.ForceExpunge(unreachable.instanceId))
        noMoreInteractions(f.stateOpProcessor)
      }
    }

    "checking two inactive Unreachable tasks and one is overdue" should withActor { (f: Fixture, checkActor: ActorRef) =>
      val unreachable1 = TestInstanceBuilder.newBuilder("/unreachable1".toPath).addTaskUnreachableInactive(since = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val unreachable2 = TestInstanceBuilder.newBuilder("/unreachable2".toPath).addTaskUnreachableInactive(since = f.clock.now())
        .getInstance()
        .copy(unreachableStrategy = f.strategy)

      f.taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(unreachable1, unreachable2))

      val testProbe = TestProbe()
      testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
      testProbe.receiveOne(3.seconds)

      "issue one expunge" in {
        verify(f.stateOpProcessor, once).process(InstanceUpdateOperation.ForceExpunge(unreachable1.instanceId))
        noMoreInteractions(f.stateOpProcessor)
      }
    }

    "checking two lost task and one is overdue" should withActor { (f: Fixture, checkActor: ActorRef) =>
      // Note that both won't have unreachable time set.
      val unreachable1 = TestInstanceBuilder.newBuilder("/unreachable1".toPath).addTaskLost(since = Timestamp.zero)
        .getInstance()
        .copy(unreachableStrategy = f.strategy)
      val unreachable2 = TestInstanceBuilder.newBuilder("/unreachable2".toPath).addTaskLost(since = f.clock.now())
        .getInstance()
        .copy(unreachableStrategy = f.strategy)

      f.taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(unreachable1, unreachable2))

      val testProbe = TestProbe()

      // Trigger UnreachableInactive mark
      testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
      testProbe.receiveOne(3.seconds)

      "ensure backwards compatibility and issue one expunge" ignore {
        val (taskId, task) = unreachable1.tasksMap.head
        verify(f.stateOpProcessor, once).process(InstanceUpdateOperation.ForceExpunge(unreachable1.instanceId))
        noMoreInteractions(f.stateOpProcessor)
      }
    }
  }
}
