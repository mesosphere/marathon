package mesosphere.marathon.upgrade

import java.util.UUID

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit, TestProbe }
import akka.util.Timeout
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ MarathonTasks, TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentActor.Finished
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFinished, DeploymentStepInfo }
import mesosphere.marathon.{ MarathonSpec, SchedulerActions }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TaskID
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.Future
import scala.concurrent.duration._

class DeploymentActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  var repo: AppRepository = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var storage: StorageProvider = _
  var hcManager: HealthCheckManager = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    repo = mock[AppRepository]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    scheduler = mock[SchedulerActions]
    storage = mock[StorageProvider]
    hcManager = mock[HealthCheckManager]
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("Deploy") {
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, version = Timestamp(0))
    val app2 = AppDefinition(id = PathId("app2"), cmd = Some("cmd"), instances = 1, version = Timestamp(0))
    val app3 = AppDefinition(id = PathId("app3"), cmd = Some("cmd"), instances = 1, version = Timestamp(0))
    val app4 = AppDefinition(id = PathId("app4"), cmd = Some("cmd"), version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app1, app2, app4))

    val app1New = app1.copy(instances = 1, version = Timestamp(1000))
    val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(app1New, app2New, app3))

    // setting started at to 0 to make sure this survives
    val task1_1 = MarathonTasks.makeTask("task1_1", "", Nil, Nil, app1.version).toBuilder.setStartedAt(0).build()
    val task1_2 = MarathonTasks.makeTask("task1_2", "", Nil, Nil, app1.version).toBuilder.setStartedAt(1000).build()
    val task2_1 = MarathonTasks.makeTask("task2_1", "", Nil, Nil, app2.version)
    val task3_1 = MarathonTasks.makeTask("task3_1", "", Nil, Nil, app3.version)
    val task4_1 = MarathonTasks.makeTask("task4_1", "", Nil, Nil, app4.version)

    val plan = DeploymentPlan(origGroup, targetGroup)

    when(tracker.get(app1.id)).thenReturn(Set(task1_1, task1_2))
    when(tracker.get(app2.id)).thenReturn(Set(task2_1))
    when(tracker.get(app3.id)).thenReturn(Set(task3_1))
    when(tracker.get(app4.id)).thenReturn(Set(task4_1))

    // the AppDefinition is never used, so it does not mater which one we return
    when(repo.store(any())).thenReturn(Future.successful(AppDefinition()))

    when(driver.killTask(TaskID(task1_2.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task1_2", "TASK_KILLED", "", app1.id, "", Nil, app1New.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(driver.killTask(TaskID(task2_1.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task2_1", "TASK_KILLED", "", app2.id, "", Nil, app2.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(queue.add(app2New)).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        system.eventStream.publish(MesosStatusUpdateEvent("", UUID.randomUUID().toString, "TASK_RUNNING", "", app2.id, "", Nil, app2New.version.toString))
        true
      }
    })

    when(scheduler.startApp(driver, app3)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task3_1", "TASK_RUNNING", "", app3.id, "", Nil, app3.version.toString))
        Future.successful(())
      }
    })

    when(scheduler.stopApp(driver, app4)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task4_1", "TASK_FINISHED", "", app4.id, "", Nil, app4.version.toString))
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
        storage,
        hcManager,
        system.eventStream
      )
    )

    plan.steps.zipWithIndex.foreach {
      case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
    }

    managerProbe.expectMsg(5.seconds, DeploymentFinished(plan.id))

    verify(scheduler).startApp(driver, app3)
    verify(driver, times(1)).killTask(TaskID(task1_2.getId))
    verify(scheduler).stopApp(driver, app4)
  }

  test("Restart app") {
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val appNew = app.copy(cmd = Some("cmd new"), version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val task1_1 = MarathonTasks.makeTask("task1_1", "", Nil, Nil, app.version).toBuilder.setStartedAt(0).build()
    val task1_2 = MarathonTasks.makeTask("task1_2", "", Nil, Nil, app.version).toBuilder.setStartedAt(1000).build()

    when(tracker.get(app.id)).thenReturn(Set(task1_1, task1_2))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew, 0, 2)))), Timestamp.now())

    when(driver.killTask(TaskID(task1_1.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task1_1", "TASK_KILLED", "", app.id, "", Nil, appNew.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(driver.killTask(TaskID(task1_2.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", "task1_2", "TASK_KILLED", "", app.id, "", Nil, app.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    val taskIDs = Iterator.from(3)
    var taskCount = 0

    when(queue.count(appNew)).thenAnswer(new Answer[Int] {
      override def answer(p1: InvocationOnMock): Int = taskCount
    })

    when(queue.add(appNew)).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        taskCount += 1
        system.eventStream.publish(MesosStatusUpdateEvent("", s"task1_${taskIDs.next()}", "TASK_RUNNING", "", app.id, "", Nil, appNew.version.toString))
        true
      }
    })

    when(repo.store(appNew)).thenReturn(Future.successful(appNew))

    val deployer = TestActorRef(
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
        storage,
        hcManager,
        system.eventStream
      )
    )

    receiverProbe.expectMsg(Finished)

    verify(driver).killTask(TaskID(task1_1.getId))
    verify(driver).killTask(TaskID(task1_2.getId))
    verify(queue, times(2)).add(appNew)
  }
}
