package mesosphere.marathon.core.task.jobs

import akka.actor.{ ActorRef, ActorSystem, PoisonPill, Terminated }
import akka.testkit.TestProbe
import mesosphere.marathon
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.jobs.impl.ExpungeOverdueLostTasksActor
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration.{ Duration, _ }
import scala.concurrent.{ Await, ExecutionContext, Future }

class ExpungeOverdueLostTasksActorTest extends MarathonSpec
    with GivenWhenThen with marathon.test.Mockito with ScalaFutures {
  implicit var actorSystem: ActorSystem = _
  val taskTracker: InstanceTracker = mock[InstanceTracker]
  val clock = ConstantClock()
  val config = MarathonTestHelper.defaultConfig(maxTasksPerOffer = 10)
  val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
  var checkActor: ActorRef = _

  before {
    actorSystem = ActorSystem()
    checkActor = actorSystem.actorOf(ExpungeOverdueLostTasksActor.props(clock, config, taskTracker, stateOpProcessor))
  }

  after {
    def waitForActorProcessingAllAndDying(): Unit = {
      checkActor ! PoisonPill
      val probe = TestProbe()
      probe.watch(checkActor)
      val terminated = probe.expectMsgAnyClassOf(classOf[Terminated])
      assert(terminated.actor == checkActor)
    }

    waitForActorProcessingAllAndDying()

    Await.result(actorSystem.terminate(), Duration.Inf)
  }

  test("running tasks with more than 24 hours with no status update should not be killed") {
    Given("two running tasks")
    val running1 = TestInstanceBuilder.newBuilder("/running1".toPath).addTaskRunning(startedAt = Timestamp.zero).getInstance()
    val running2 = TestInstanceBuilder.newBuilder("/running2".toPath).addTaskRunning(startedAt = Timestamp.zero).getInstance()

    taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(running1, running2))

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
    testProbe.receiveOne(3.seconds)

    And("no kill calls are issued")
    noMoreInteractions(stateOpProcessor)
  }

  test("an unreachable task with more than 24 hours with no status update should be killed") {
    Given("one unreachable, one running tasks")
    val running = TestInstanceBuilder.newBuilder("/running".toPath).addTaskRunning(startedAt = Timestamp.zero).getInstance()
    val unreachable = TestInstanceBuilder.newBuilder("/unreachable".toPath).addTaskUnreachable(since = Timestamp.zero).getInstance()

    taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(running, unreachable))

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
    testProbe.receiveOne(3.seconds)

    And("one expunge is issued")
    verify(stateOpProcessor, once).process(InstanceUpdateOperation.ForceExpunge(unreachable.instanceId))
    noMoreInteractions(stateOpProcessor)
  }

  test("an unreachable task with less than 24 hours with no status update should not be killed") {
    Given("two unreachable tasks, one overdue")
    val unreachable1 = TestInstanceBuilder.newBuilder("/unreachable1".toPath).addTaskUnreachable(since = Timestamp.zero).getInstance()
    val unreachable2 = TestInstanceBuilder.newBuilder("/unreachable2".toPath).addTaskUnreachable(since = Timestamp.now()).getInstance()

    taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(unreachable1, unreachable2))

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
    testProbe.receiveOne(3.seconds)

    And("one expunge is issued")
    verify(stateOpProcessor, once).process(InstanceUpdateOperation.ForceExpunge(unreachable1.instanceId))
    noMoreInteractions(stateOpProcessor)
  }

  test("backwards compatibility with old TASK_LOST status") {
    Given("two unreachable tasks, one overdue")
    // Note that both won't have unreachable time set.
    val unreachable1 = TestInstanceBuilder.newBuilder("/unreachable1".toPath).addTaskLost(since = Timestamp.zero).getInstance()
    val unreachable2 = TestInstanceBuilder.newBuilder("/unreachable2".toPath).addTaskLost(since = Timestamp.now()).getInstance()

    taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(unreachable1, unreachable2))

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
    testProbe.receiveOne(3.seconds)

    And("one expunge is issued")
    verify(stateOpProcessor, once).process(InstanceUpdateOperation.ForceExpunge(unreachable1.instanceId))
    noMoreInteractions(stateOpProcessor)
  }
}
