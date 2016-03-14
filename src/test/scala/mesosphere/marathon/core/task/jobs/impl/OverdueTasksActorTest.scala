package mesosphere.marathon.core.task.jobs.impl

import akka.actor._
import akka.testkit.TestProbe
import mesosphere.marathon
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.{ TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.{ TaskReservationTimeoutHandler, TaskTracker }
import mesosphere.marathon.core.task.tracker.TaskTracker.TasksByApp
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.TaskID
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.apache.mesos.{ Protos => MesosProtos, SchedulerDriver }
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

class OverdueTasksActorTest extends MarathonSpec with GivenWhenThen with marathon.test.Mockito with ScalaFutures {
  implicit var actorSystem: ActorSystem = _
  var taskTracker: TaskTracker = _
  var taskReservationTimeoutHandler: TaskReservationTimeoutHandler = _
  var driver: SchedulerDriver = _
  var checkActor: ActorRef = _
  val clock = ConstantClock()

  before {
    actorSystem = ActorSystem()
    taskTracker = mock[TaskTracker]
    taskReservationTimeoutHandler = mock[TaskReservationTimeoutHandler]
    driver = mock[SchedulerDriver]
    val driverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)
    val config = MarathonTestHelper.defaultConfig()
    checkActor = actorSystem.actorOf(
      OverdueTasksActor.props(config, taskTracker, taskReservationTimeoutHandler, driverHolder, clock),
      "check")
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

    actorSystem.shutdown()
    actorSystem.awaitTermination()

    noMoreInteractions(taskTracker)
    noMoreInteractions(driver)
    noMoreInteractions(taskReservationTimeoutHandler)
  }

  test("no overdue tasks") {
    Given("no tasks")
    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TasksByApp.empty)

    When("a check is performed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
    testProbe.expectMsg(3.seconds, ())

    Then("eventually list was called")
    verify(taskTracker).tasksByApp()(any[ExecutionContext])
    And("no kill calls are issued")
    noMoreInteractions(driver)
  }

  test("some overdue tasks") {
    Given("one overdue task")
    val mockTask = MarathonTestHelper.stagedTaskProto("someId")
    val app = TaskTracker.AppTasks(PathId("/some"), Iterable(mockTask))
    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TasksByApp.of(app))

    When("the check is initiated")
    checkActor ! OverdueTasksActor.Check(maybeAck = None)

    Then("the task kill gets initiated")
    verify(taskTracker, Mockito.timeout(1000)).tasksByApp()(any[ExecutionContext])
    import mesosphere.mesos.protos.Implicits._
    verify(driver, Mockito.timeout(1000)).killTask(TaskID("someId"))
  }

  // sounds strange, but this is how it currently works: determineOverdueTasks will consider a missing startedAt to
  // determine whether a task is in staging and might need to be killed if it exceeded the taskLaunchTimeout
  test("ensure that check kills tasks disregarding the stagedAt property") {
    import scala.language.implicitConversions
    implicit def toMillis(timestamp: Timestamp): Long = timestamp.toDateTime.getMillis

    val now = clock.now()
    val config = MarathonTestHelper.defaultConfig()

    def statusBuilder(id: String, state: TaskState) =
      TaskStatus.newBuilder().setTaskId(MesosProtos.TaskID.newBuilder().setValue(id)).setState(state)

    val overdueUnstagedTask = MarathonTestHelper.startingTaskProto("unstaged")
    assert(overdueUnstagedTask.getStartedAt == 0, "The startedAt property of an unstaged task has a value of 0")

    val unconfirmedNotOverdueTask =
      MarathonTestHelper.startingTaskProto("unconfirmed", stagedAt = now - config.taskLaunchConfirmTimeout().millis)

    val unconfirmedOverdueTask =
      MarathonTestHelper.startingTaskProto(
        "unconfirmedOverdue",
        stagedAt = now - config.taskLaunchConfirmTimeout().millis - 1.millis
      )

    val overdueStagedTask =
      MarathonTestHelper.stagedTaskProto(
        "overdueStagedTask",
        stagedAt = now - 10.days
      )

    val stagedTask =
      MarathonTestHelper.stagedTaskProto(
        "staged",
        stagedAt = now - 10.seconds
      )

    val runningTask =
      MarathonTestHelper.runningTaskProto("running", stagedAt = now - 5.seconds, startedAt = now - 2.seconds)

    Given("Several somehow overdue tasks plus some not overdue tasks")
    val appId = PathId("/ignored")
    val app = TaskTracker.AppTasks(
      appId,
      Iterable(
        unconfirmedOverdueTask,
        unconfirmedNotOverdueTask,
        overdueUnstagedTask,
        overdueStagedTask,
        stagedTask,
        runningTask
      )
    )
    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TasksByApp.of(app))

    When("We check which tasks should be killed because they're not yet staged or unconfirmed")
    val testProbe = TestProbe()
    testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
    testProbe.expectMsg(3.seconds, ())

    Then("The task tracker gets queried")
    verify(taskTracker).tasksByApp()(any[ExecutionContext])

    And("All somehow overdue tasks are killed")
    verify(driver).killTask(MesosProtos.TaskID.newBuilder().setValue(unconfirmedOverdueTask.getId).build())
    verify(driver).killTask(MesosProtos.TaskID.newBuilder().setValue(overdueUnstagedTask.getId).build())
    verify(driver).killTask(MesosProtos.TaskID.newBuilder().setValue(overdueStagedTask.getId).build())

    And("but not more")
    verifyNoMoreInteractions(driver)
  }

  test("reservations with a timeout in the past are processed") {
    Given("one overdue reservation")
    val appId = PathId("/test")
    val overdueReserved = reservedWithTimeout(appId, deadline = clock.now() - 1.second)
    val recentReserved = reservedWithTimeout(appId, deadline = clock.now() + 1.second)
    val app = TaskTracker.AppTasks.forTasks(appId, Iterable(recentReserved, overdueReserved))
    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TasksByApp.of(app))
    taskReservationTimeoutHandler.timeout(TaskStateOp.ReservationTimeout(overdueReserved.taskId)).asInstanceOf[Future[Unit]] returns
      Future.successful(())

    When("the check is initiated")
    val testProbe = TestProbe()
    testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
    testProbe.expectMsg(3.seconds, ())

    Then("the reservation gets processed")
    verify(taskTracker).tasksByApp()(any[ExecutionContext])
    verify(taskReservationTimeoutHandler).timeout(TaskStateOp.ReservationTimeout(overdueReserved.taskId))

  }

  private[this] def reservedWithTimeout(appId: PathId, deadline: Timestamp): Task.Reserved = {
    val template = MarathonTestHelper.residentReservedTask(appId)
    template.copy(
      reservation = template.reservation.copy(
        state = Task.Reservation.State.New(timeout = Some(Task.Reservation.Timeout(
          initiated = Timestamp.zero,
          deadline = deadline,
          reason = Task.Reservation.Timeout.Reason.ReservationTimeout
        )))
      )
    )
  }
}
