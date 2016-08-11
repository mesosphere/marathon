package mesosphere.marathon.core.task.jobs

import akka.actor.{ ActorRef, ActorSystem, PoisonPill, Terminated }
import akka.testkit.TestProbe
import mesosphere.marathon
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.jobs.impl.ExpungeOverdueLostTasksActor
import mesosphere.marathon.core.task.tracker.TaskTracker.TasksByApp
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

class ExpungeOverdueLostTasksActorTest extends MarathonSpec with GivenWhenThen with marathon.test.Mockito with ScalaFutures {
  implicit var actorSystem: ActorSystem = _
  val taskTracker: TaskTracker = mock[TaskTracker]
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

  test("running tasks with more then 24 hours with no status update should not be killed") {
    Given("two running tasks")
    val running1 = MarathonTestHelper.minimalRunning("/running1".toPath, since = Timestamp.apply(0))
    val running2 = MarathonTestHelper.minimalRunning("/running2".toPath, since = Timestamp.apply(0))

    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TasksByApp.forTasks(running1, running2))

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
    testProbe.receiveOne(3.seconds)

    And("no kill calls are issued")
    noMoreInteractions(stateOpProcessor)
  }

  test("a unreachable task with more then 24 hours with no status update should be killed") {
    Given("one unreachable, one running tasks")
    val running = MarathonTestHelper.minimalRunning("/running1".toPath, since = Timestamp.apply(0))
    val unreachable = MarathonTestHelper.minimalUnreachableTask("/running2".toPath, since = Timestamp.apply(0))

    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TasksByApp.forTasks(running, unreachable))

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, ExpungeOverdueLostTasksActor.Tick)
    testProbe.receiveOne(3.seconds)

    And("one kill call is issued")
    verify(stateOpProcessor, once).process(any)
  }
}
