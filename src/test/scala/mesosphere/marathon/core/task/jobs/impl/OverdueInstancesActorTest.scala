package mesosphere.marathon
package core.task.jobs.impl

import java.util.UUID

import akka.Done
import akka.actor._
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{Instance, Reservation, TestInstanceBuilder}
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Mockito}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class OverdueInstancesActorTest extends AkkaUnitTest {

  case class Fixture(
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      driver: SchedulerDriver = mock[SchedulerDriver],
      killService: KillService = mock[KillService],
      clock: SettableClock = new SettableClock()) {
    val driverHolder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)
    val config: AllConf = MarathonTestHelper.defaultConfig()
    val metrics: Metrics = DummyMetrics
    val checkActor: ActorRef = system.actorOf(
      OverdueInstancesActor.props(config, instanceTracker, killService, metrics, clock),
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

      noMoreInteractions(instanceTracker)
      noMoreInteractions(driver)
    }
  }

  "OverdueInstancesActor" should {
    "no overdue tasks" in new Fixture {
      Given("no tasks")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.empty)

      When("a check is performed")
      val testProbe = TestProbe()
      testProbe.send(checkActor, OverdueInstancesActor.Check(maybeAck = Some(testProbe.ref)))
      testProbe.expectMsg(3.seconds, ())

      Then("eventually list was called")
      verify(instanceTracker).instancesBySpec()(any[ExecutionContext])
      And("no kill calls are issued")
      noMoreInteractions(driver)
      verifyClean()
    }

    "some overdue tasks" in new Fixture {
      Given("one overdue task")
      val appId = AbsolutePathId("/some")
      val mockInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(Seq(mockInstance))
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("the check is initiated")
      checkActor ! OverdueInstancesActor.Check(maybeAck = None)

      Then("the task kill gets initiated")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      verify(killService, Mockito.timeout(1000)).killInstancesAndForget(Seq(mockInstance), KillReason.Overdue)
      verifyClean()
    }

    // sounds strange, but this is how it currently works: determineOverdueTasks will consider a missing startedAt to
    // determine whether a task is in staging and might need to be killed if it exceeded the taskLaunchTimeout
    "ensure that check kills tasks disregarding the stagedAt property" in new Fixture {
      val now = clock.now()

      val appId = AbsolutePathId("/ignored")
      val overdueUnstagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      assert(overdueUnstagedTask.tasksMap.valuesIterator.forall(_.status.startedAt.isEmpty))

      val unconfirmedNotOverdueTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(now - config.taskLaunchConfirmTimeout().millis).getInstance()

      val unconfirmedOverdueTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(now - config.taskLaunchConfirmTimeout().millis - 1.millis).getInstance()

      val overdueStagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStaged(now - 10.days).getInstance()

      val stagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStaged(now - 10.seconds).getInstance()

      val runningTask = TestInstanceBuilder.newBuilder(appId).addTaskRunning(stagedAt = now - 5.seconds, startedAt = now - 2.seconds).getInstance()

      Given("Several somehow overdue tasks plus some not overdue tasks")
      val app = InstanceTracker.InstancesBySpec.forInstances(
        Seq(
          unconfirmedOverdueTask,
          unconfirmedNotOverdueTask,
          overdueUnstagedTask,
          overdueStagedTask,
          stagedTask,
          runningTask)
      )
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("We check which tasks should be killed because they're not yet staged or unconfirmed")
      val testProbe = TestProbe()
      testProbe.send(checkActor, OverdueInstancesActor.Check(maybeAck = Some(testProbe.ref)))
      testProbe.expectMsg(3.seconds, ())

      Then("The task tracker gets queried")
      verify(instanceTracker).instancesBySpec()(any[ExecutionContext])

      And("All somehow overdue tasks are killed")
      val instances = ArgumentCaptor.forClass(classOf[Seq[Instance]])
      val reason = ArgumentCaptor.forClass(classOf[KillReason])
      verify(killService).killInstancesAndForget(instances.capture(), reason.capture())

      instances.getValue should contain theSameElementsAs (Seq(unconfirmedOverdueTask, overdueUnstagedTask, overdueStagedTask))
      reason.getValue shouldBe KillReason.Overdue

      And("but not more")
      verifyNoMoreInteractions(driver)
      verifyClean()
    }

    "reservations with a timeout in the past are processed" in new Fixture {
      Given("one overdue reservation")
      val appId = AbsolutePathId("/test")
      val overdueReserved = reservedWithTimeout(appId, deadline = clock.now() - 1.second)
      val recentReserved = reservedWithTimeout(appId, deadline = clock.now() + 1.second)
      val app = InstanceTracker.InstancesBySpec.forInstances(Seq(recentReserved, overdueReserved))
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)
      instanceTracker.reservationTimeout(overdueReserved.instanceId) returns Future.successful(Done)

      When("the check is initiated")
      val testProbe = TestProbe()
      testProbe.send(checkActor, OverdueInstancesActor.Check(maybeAck = Some(testProbe.ref)))
      testProbe.expectMsg(3.seconds, ())

      Then("the reservation gets processed")
      verify(instanceTracker).instancesBySpec()(any[ExecutionContext])
      verify(instanceTracker).reservationTimeout(overdueReserved.instanceId)
      verifyClean()
    }
  }

  private[this] def reservedWithTimeout(appId: AbsolutePathId, deadline: Timestamp): Instance = {
    val state = Reservation.State.New(timeout = Some(Reservation.Timeout(
      initiated = Timestamp.zero,
      deadline = deadline,
      reason = Reservation.Timeout.Reason.ReservationTimeout)))
    TestInstanceBuilder.scheduledWithReservation(AppDefinition(appId, role = "*"), state = state)
  }
}
