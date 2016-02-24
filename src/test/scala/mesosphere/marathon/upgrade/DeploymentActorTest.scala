package mesosphere.marathon.upgrade

import akka.actor.ActorSystem
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFinished, DeploymentStepInfo }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, SchedulerActions }
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.{ any, same }
import org.mockito.Mockito.{ times, verify, verifyNoMoreInteractions, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.Future
import scala.concurrent.duration._

class DeploymentActorTest
    extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  var tracker: TaskTracker = _
  var queue: LaunchQueue = _
  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var storage: StorageProvider = _
  var hcManager: HealthCheckManager = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    tracker = mock[TaskTracker]
    queue = mock[LaunchQueue]
    scheduler = mock[SchedulerActions]
    storage = mock[StorageProvider]
    hcManager = mock[HealthCheckManager]
  }

  test("Deploy") {
    implicit val system = ActorSystem("TestSystem")
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
    val app2 = AppDefinition(id = PathId("/app2"), cmd = Some("cmd"), instances = 1)
    val app3 = AppDefinition(id = PathId("/app3"), cmd = Some("cmd"), instances = 1)
    val app4 = AppDefinition(id = PathId("/app4"), cmd = Some("cmd"))
    val origGroup = Group(PathId("/foo/bar"), Set(app1, app2, app4))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val app1New = app1.copy(instances = 1, versionInfo = version2)
    val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), versionInfo = version2)

    val targetGroup = Group(PathId("/foo/bar"), Set(app1New, app2New, app3))

    // setting started at to 0 to make sure this survives
    val task1_1 = MarathonTestHelper.runningTask("task1_1", appVersion = app1.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask("task1_2", appVersion = app1.version, startedAt = 1000)
    val task2_1 = MarathonTestHelper.runningTask("task2_1", appVersion = app2.version)
    val task3_1 = MarathonTestHelper.runningTask("task3_1", appVersion = app3.version)
    val task4_1 = MarathonTestHelper.runningTask("task4_1", appVersion = app4.version)

    val plan = DeploymentPlan(origGroup, targetGroup)

    when(tracker.appTasksLaunchedSync(app1.id)).thenReturn(Set(task1_1, task1_2))
    when(tracker.appTasksLaunchedSync(app2.id)).thenReturn(Set(task2_1))
    when(tracker.appTasksLaunchedSync(app3.id)).thenReturn(Set(task3_1))
    when(tracker.appTasksLaunchedSync(app4.id)).thenReturn(Set(task4_1))

    when(driver.killTask(task1_2.taskId.mesosTaskId)).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent(
          slaveId = "", taskId = Task.Id("task1_2"), taskStatus = "TASK_KILLED", message = "", appId = app1.id, host = "",
          ipAddresses = Nil, ports = Nil, version = app1New.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(driver.killTask(task2_1.taskId.mesosTaskId)).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent(
          slaveId = "", taskId = Task.Id("task2_1"), taskStatus = "TASK_KILLED", message = "", appId = app2.id, host = "",
          ipAddresses = Nil, ports = Nil, version = app2.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(queue.add(same(app2New), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        println(invocation.getArguments.toSeq)
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          system.eventStream.publish(MesosStatusUpdateEvent(
            slaveId = "", taskId = Task.Id.forApp(app2New.id), taskStatus = "TASK_RUNNING", message = "",
            appId = app2.id, host = "", ipAddresses = Nil, ports = Nil, version = app2New.version.toString)
          )
        true
      }
    })

    when(scheduler.startApp(driver, app3)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        // system.eventStream.publish(MesosStatusUpdateEvent("", "task3_1", "TASK_RUNNING", "", app3.id, "", "", Nil, app3.version.toString))
        Future.successful(())
      }
    })

    when(scheduler.scale(driver, app3)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        system.eventStream.publish(MesosStatusUpdateEvent(
          slaveId = "", taskId = Task.Id("task3_1"), taskStatus = "TASK_RUNNING", message = "", appId = app3.id, host = "",
          ipAddresses = Nil, ports = Nil, version = app3.version.toString))
        Future.successful(())
      }
    })

    when(driver.killTask(task4_1.taskId.mesosTaskId)).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent(
          slaveId = "", taskId = Task.Id("task4_1"), taskStatus = "TASK_FINISHED", message = "", appId = app4.id,
          host = "", ipAddresses = Nil, ports = Nil, version = app4.version.toString
        ))
        Status.DRIVER_RUNNING
      }
    })

    try {
      TestActorRef(
        DeploymentActor.props(
          managerProbe.ref,
          receiverProbe.ref,
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

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      verify(scheduler).startApp(driver, app3.copy(instances = 0))
      verify(driver, times(1)).killTask(task1_2.taskId.mesosTaskId)
      verify(scheduler).stopApp(driver, app4.copy(instances = 0))
    }
    finally {
      system.shutdown()
    }
  }

  test("Restart app") {
    implicit val system = ActorSystem("TestSystem")
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val task1_1 = MarathonTestHelper.runningTask("task1_1", appVersion = app.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask("task1_2", appVersion = app.version, startedAt = 1000)

    when(tracker.appTasksLaunchedSync(app.id)).thenReturn(Set(task1_1, task1_2))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(driver.killTask(task1_1.taskId.mesosTaskId)).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id("task1_1"), "TASK_KILLED", "", app.id, "", Nil, Nil, app.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    when(driver.killTask(task1_2.taskId.mesosTaskId)).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id("task1_2"), "TASK_KILLED", "", app.id, "", Nil, Nil, app.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    val taskIDs = Iterator.from(3)

    when(queue.count(appNew.id)).thenAnswer(new Answer[Int] {
      override def answer(p1: InvocationOnMock): Int = appNew.instances
    })

    when(queue.add(same(appNew), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task1_${taskIDs.next()}"),
            "TASK_RUNNING", "", app.id, "", Nil, Nil, appNew.version.toString))
        true
      }
    })

    try {
      TestActorRef(
        DeploymentActor.props(
          managerProbe.ref,
          receiverProbe.ref,
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

      receiverProbe.expectMsg(DeploymentFinished(plan))

      verify(driver).killTask(task1_1.taskId.mesosTaskId)
      verify(driver).killTask(task1_2.taskId.mesosTaskId)
      verify(queue).add(appNew, 2)
    }
    finally {
      system.shutdown()
    }
  }

  test("Restart suspended app") {
    implicit val system = ActorSystem("TestSystem")
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()

    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 0)
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable.empty[Task])

    try {
      TestActorRef(
        DeploymentActor.props(
          managerProbe.ref,
          receiverProbe.ref,
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

      receiverProbe.expectMsg(DeploymentFinished(plan))
    }
    finally {
      system.shutdown()
    }
  }

  test("Scale with tasksToKill") {
    implicit val system = ActorSystem("TestSystem")
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 3)
    val origGroup = Group(PathId("/foo/bar"), Set(app1))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val app1New = app1.copy(instances = 2, versionInfo = version2)

    val targetGroup = Group(PathId("/foo/bar"), Set(app1New))

    val task1_1 = MarathonTestHelper.runningTask("task1_1", appVersion = app1.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask("task1_2", appVersion = app1.version, startedAt = 500)
    val task1_3 = MarathonTestHelper.runningTask("task1_3", appVersion = app1.version, startedAt = 1000)

    val plan = DeploymentPlan(original = origGroup, target = targetGroup, toKill = Map(app1.id -> Set(task1_2)))

    when(tracker.appTasksLaunchedSync(app1.id)).thenReturn(Set(task1_1, task1_2, task1_3))

    when(driver.killTask(task1_2.taskId.mesosTaskId)).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id("task1_2"), "TASK_KILLED", "", app1.id, "", Nil, Nil, app1New.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    try {
      TestActorRef(
        DeploymentActor.props(
          managerProbe.ref,
          receiverProbe.ref,
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

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      verify(driver, times(1)).killTask(task1_2.taskId.mesosTaskId)
      verifyNoMoreInteractions(driver)
    }
    finally {
      system.shutdown()
    }
  }
}
