package mesosphere.marathon.upgrade

import akka.testkit.{ TestProbe, TestActorRef, TestKit }
import akka.actor.{ Props, ActorSystem }
import mesosphere.marathon.{ SchedulerActions, MarathonSpec }
import org.scalatest.{ BeforeAndAfterAll, Matchers }
import org.scalatest.mock.MockitoSugar
import mesosphere.marathon.state.{ Timestamp, PathId, Group, AppRepository }
import mesosphere.marathon.tasks.{ MarathonTasks, TaskQueue, TaskTracker }
import org.apache.mesos.SchedulerDriver
import akka.util.Timeout
import scala.concurrent.duration._
import mesosphere.marathon.api.v1.AppDefinition
import org.mockito.Mockito.{ verify, when, times }
import org.mockito.Matchers.any
import scala.collection.mutable
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentFinished
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.mesos.protos.TaskID
import mesosphere.mesos.protos.Implicits._
import scala.concurrent.Future
import org.mockito.stubbing.Answer
import org.mockito.invocation.InvocationOnMock
import org.apache.mesos.Protos.Status

class DeploymentActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  var repo: AppRepository = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    repo = mock[AppRepository]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    scheduler = mock[SchedulerActions]
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("Deploy") {
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("app1"), cmd = "cmd", instances = 2)
    val app2 = AppDefinition(id = PathId("app2"), cmd = "cmd", instances = 1, version = Timestamp(0))
    val app3 = AppDefinition(id = PathId("app3"), cmd = "cmd", instances = 1)
    val app4 = AppDefinition(id = PathId("app4"), cmd = "cmd")
    val origGroup = Group(PathId("/foo/bar"), Set(app1, app2, app4))

    val app1New = app1.copy(instances = 1)
    val app2New = app2.copy(instances = 2, cmd = "otherCmd", version = Timestamp.now())

    val targetGroup = Group(PathId("/foo/bar"), Set(app1New, app2New, app3))

    // setting started at to 0 to make sure this survives
    val task1_1 = MarathonTasks.makeTask("task1_1", "", Nil, Nil, app1.version).toBuilder.setStartedAt(0).build()
    val task1_2 = MarathonTasks.makeTask("task1_2", "", Nil, Nil, app1.version).toBuilder.setStartedAt(1000).build()
    val task2_1 = MarathonTasks.makeTask("task2_1", "", Nil, Nil, app2.version)
    val task3_1 = MarathonTasks.makeTask("task3_1", "", Nil, Nil, app3.version)
    val task4_1 = MarathonTasks.makeTask("task4_1", "", Nil, Nil, app4.version)

    val plan = DeploymentPlan(origGroup, targetGroup)

    when(tracker.fetchApp(app1.id)).thenReturn(new TaskTracker.App(app1.id, mutable.Set(task1_1, task1_2), false))
    when(tracker.fetchApp(app2.id)).thenReturn(new TaskTracker.App(app2.id, mutable.Set(task2_1), false))
    when(tracker.fetchApp(app3.id)).thenReturn(new TaskTracker.App(app3.id, mutable.Set(task3_1), false))
    when(tracker.fetchApp(app4.id)).thenReturn(new TaskTracker.App(app4.id, mutable.Set(task4_1), false))

    // the AppDefinition is never used, so it does not mater which one we return
    when(repo.store(any())).thenReturn(Future.successful(AppDefinition()))

    when(driver.killTask(TaskID(task1_2.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task1_2", "TASK_KILLED", app1.id, "", Nil, app1New.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(driver.killTask(TaskID(task2_1.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task2_1", "TASK_KILLED", app2.id, "", Nil, app2.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(queue.add(app2New)).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task2_2", "TASK_RUNNING", app2.id, "", Nil, app2New.version.toString))
        true
      }
    })

    when(scheduler.startApp(driver, app3)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task3_1", "TASK_RUNNING", app3.id, "", Nil, app3.version.toString))
        Future.successful(())
      }
    })

    when(scheduler.stopApp(driver, app4)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task4_1", "TASK_FINISHED", app4.id, "", Nil, app4.version.toString))
        Future.successful(())
      }
    })

    TestActorRef(
      Props(
        classOf[DeploymentActor],
        managerProbe.ref,
        receiverProbe.ref,
        repo,
        driver,
        scheduler,
        plan,
        tracker,
        queue,
        system.eventStream
      )
    )

    managerProbe.expectMsg(5.seconds, DeploymentFinished(plan.id))

    verify(scheduler).startApp(driver, app3)
    verify(driver, times(1)).killTask(TaskID(task1_2.getId))
    verify(scheduler).stopApp(driver, app4)
  }
}
