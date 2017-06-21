package mesosphere.marathon
package core.task.jobs.impl

import java.util.UUID

import akka.actor._
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.Mockito._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class OverdueTasksActorTest extends AkkaUnitTest {

  case class Fixture(
      taskTracker: InstanceTracker = mock[InstanceTracker],
      taskStateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor],
      driver: SchedulerDriver = mock[SchedulerDriver],
      killService: KillService = mock[KillService],
      clock: ConstantClock = ConstantClock()) {
    val driverHolder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)
    val config: AllConf = MarathonTestHelper.defaultConfig()
    val checkActor: ActorRef = system.actorOf(
      OverdueTasksActor.props(config, taskTracker, taskStateOpProcessor, killService, clock),
      "check-" + UUID.randomUUID.toString)

    def verifyClean(): Unit = {
      def waitForActorProcessingAllAndDying(): Unit = {
        checkActor ! PoisonPill
        val probe = TestProbe()
        probe.watch(checkActor)
        val terminated = probe.expectMsgAnyClassOf(classOf[Terminated])
        assert(terminated.actor == checkActor)
      }

      waitForActorProcessingAllAndDying()

      noMoreInteractions(taskTracker)
      noMoreInteractions(driver)
      noMoreInteractions(taskStateOpProcessor)
    }
  }

  "OverdueTasksActor" should {
    "no overdue tasks" in new Fixture {
      Given("no tasks")
      taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.empty)

      When("a check is performed")
      val testProbe = TestProbe()
      testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
      testProbe.expectMsg(3.seconds, ())

      Then("eventually list was called")
      verify(taskTracker).instancesBySpec()(any[ExecutionContext])
      And("no kill calls are issued")
      noMoreInteractions(driver)
      verifyClean()
    }

    "some overdue tasks" in new Fixture {
      Given("one overdue task")
      val appId = PathId("/some")
      val mockInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
      val app = InstanceTracker.SpecInstances.forInstances(appId, Seq(mockInstance))
      taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.of(app))

      When("the check is initiated")
      checkActor ! OverdueTasksActor.Check(maybeAck = None)

      Then("the task kill gets initiated")
      verify(taskTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      verify(killService, Mockito.timeout(1000)).killInstance(mockInstance, KillReason.Overdue)
      verifyClean()
    }

    // sounds strange, but this is how it currently works: determineOverdueTasks will consider a missing startedAt to
    // determine whether a task is in staging and might need to be killed if it exceeded the taskLaunchTimeout
    "ensure that check kills tasks disregarding the stagedAt property" in new Fixture {
      import scala.language.implicitConversions
      implicit def toMillis(timestamp: Timestamp): Long = timestamp.millis

      val now = clock.now()

      val appId = PathId("/ignored")
      val overdueUnstagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      assert(overdueUnstagedTask.tasksMap.valuesIterator.forall(_.status.startedAt.isEmpty))

      val unconfirmedNotOverdueTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(now - config.taskLaunchConfirmTimeout().millis).getInstance()

      val unconfirmedOverdueTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(now - config.taskLaunchConfirmTimeout().millis - 1.millis).getInstance()

      val overdueStagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStaged(now - 10.days).getInstance()

      val stagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStaged(now - 10.seconds).getInstance()

      val runningTask = TestInstanceBuilder.newBuilder(appId).addTaskRunning(stagedAt = now - 5.seconds, startedAt = now - 2.seconds).getInstance()

      Given("Several somehow overdue tasks plus some not overdue tasks")
      val app = InstanceTracker.SpecInstances.forInstances(
        appId,
        Seq(
          unconfirmedOverdueTask,
          unconfirmedNotOverdueTask,
          overdueUnstagedTask,
          overdueStagedTask,
          stagedTask,
          runningTask
        )
      )
      taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.of(app))

      When("We check which tasks should be killed because they're not yet staged or unconfirmed")
      val testProbe = TestProbe()
      testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
      testProbe.expectMsg(3.seconds, ())

      Then("The task tracker gets queried")
      verify(taskTracker).instancesBySpec()(any[ExecutionContext])

      And("All somehow overdue tasks are killed")
      verify(killService).killInstance(unconfirmedOverdueTask, KillReason.Overdue)
      verify(killService).killInstance(overdueUnstagedTask, KillReason.Overdue)
      verify(killService).killInstance(overdueStagedTask, KillReason.Overdue)

      And("but not more")
      verifyNoMoreInteractions(driver)
      verifyClean()
    }

    "reservations with a timeout in the past are processed" in new Fixture {
      Given("one overdue reservation")
      val appId = PathId("/test")
      val overdueReserved = reservedWithTimeout(appId, deadline = clock.now() - 1.second)
      val recentReserved = reservedWithTimeout(appId, deadline = clock.now() + 1.second)
      val app = InstanceTracker.SpecInstances.forInstances(appId, Seq(recentReserved, overdueReserved))
      taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.of(app))
      taskStateOpProcessor.process(InstanceUpdateOperation.ReservationTimeout(overdueReserved.instanceId)) returns
        Future.successful(InstanceUpdateEffect.Expunge(overdueReserved, Nil))

      When("the check is initiated")
      val testProbe = TestProbe()
      testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
      testProbe.expectMsg(3.seconds, ())

      Then("the reservation gets processed")
      verify(taskTracker).instancesBySpec()(any[ExecutionContext])
      verify(taskStateOpProcessor).process(InstanceUpdateOperation.ReservationTimeout(overdueReserved.instanceId))
      verifyClean()
    }
  }
  private[this] def reservedWithTimeout(appId: PathId, deadline: Timestamp): Instance = {
    val state = Task.Reservation.State.New(timeout = Some(Task.Reservation.Timeout(
      initiated = Timestamp.zero,
      deadline = deadline,
      reason = Task.Reservation.Timeout.Reason.ReservationTimeout
    )))
    TestInstanceBuilder.newBuilder(appId).addTaskWithBuilder().taskResidentReserved(state).build().getInstance()
  }
}
