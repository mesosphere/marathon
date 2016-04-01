package mesosphere.marathon.upgrade

import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFinished, DeploymentStepInfo }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, SchedulerActions }
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ verifyNoMoreInteractions, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.Future
import scala.concurrent.duration._

class DeploymentActorTest
    extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito {

  implicit val defaultTimeout: Timeout = 5.seconds

  test("Deploy") {
    val f = new Fixture
    implicit val system = f.system
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

    when(f.tracker.appTasksLaunchedSync(app1.id)).thenReturn(Set(task1_1, task1_2))
    when(f.tracker.appTasksLaunchedSync(app2.id)).thenReturn(Set(task2_1))
    when(f.tracker.appTasksLaunchedSync(app3.id)).thenReturn(Set(task3_1))
    when(f.tracker.appTasksLaunchedSync(app4.id)).thenReturn(Set(task4_1))

    f.driverKillSendsStatusKilledFor(app1, task1_2)
    f.driverKillSendsStatusKilledFor(app2, task2_1)
    f.driverKillSendsStatusKilledFor(app4, task4_1)

    when(f.queue.add(same(app2New), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        println(invocation.getArguments.toSeq)
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          system.eventStream.publish(MesosStatusUpdateEvent(
            slaveId = "", taskId = Task.Id.forApp(app2New.id), taskStatus = "TASK_RUNNING", message = "",
            appId = app2.id, host = "", ipAddresses = None, ports = Nil, version = app2New.version.toString)
          )
        true
      }
    })

    when(f.scheduler.startApp(f.driver, app3)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        // system.eventStream.publish(MesosStatusUpdateEvent("", "task3_1", "TASK_RUNNING", "", app3.id, "", "", Nil, app3.version.toString))
        Future.successful(())
      }
    })

    when(f.scheduler.scale(f.driver, app3)).thenAnswer(new Answer[Future[Unit]] {
      def answer(invocation: InvocationOnMock): Future[Unit] = {
        system.eventStream.publish(MesosStatusUpdateEvent(
          slaveId = "", taskId = Task.Id("task3_1"), taskStatus = "TASK_RUNNING", message = "", appId = app3.id, host = "",
          ipAddresses = None, ports = Nil, version = app3.version.toString))
        Future.successful(())
      }
    })

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      verify(f.scheduler).startApp(f.driver, app3.copy(instances = 0))
      verify(f.driver, times(1)).killTask(task1_2.taskId.mesosTaskId)
      verify(f.scheduler).stopApp(f.driver, app4.copy(instances = 0))
    }
    finally {
      system.shutdown()
    }
  }

  test("Restart app") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val task1_1 = MarathonTestHelper.runningTask("task1_1", appVersion = app.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask("task1_2", appVersion = app.version, startedAt = 1000)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Set(task1_1, task1_2))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    f.driverKillSendsStatusKilledFor(app, task1_1)
    f.driverKillSendsStatusKilledFor(app, task1_2)

    val taskIDs = Iterator.from(3)

    when(f.queue.count(appNew.id)).thenAnswer(new Answer[Int] {
      override def answer(p1: InvocationOnMock): Int = appNew.instances
    })

    when(f.queue.add(same(appNew), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          f.system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task1_${taskIDs.next()}"),
            "TASK_RUNNING", "", app.id, "", None, Nil, appNew.version.toString))
        true
      }
    })

    try {

      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      receiverProbe.expectMsg(DeploymentFinished(plan))

      verify(f.driver).killTask(task1_1.taskId.mesosTaskId)
      verify(f.driver).killTask(task1_2.taskId.mesosTaskId)
      verify(f.queue).add(appNew, 2)
    }
    finally {
      f.system.shutdown()
    }
  }

  test("Restart suspended app") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()

    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 0)
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable.empty[Task])

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      receiverProbe.expectMsg(DeploymentFinished(plan))
    }
    finally {
      f.system.shutdown()
    }
  }

  test("Scale with tasksToKill") {
    val f = new Fixture
    implicit val system = f.system
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

    when(f.tracker.appTasksLaunchedSync(app1.id)).thenReturn(Set(task1_1, task1_2, task1_3))

    f.driverKillSendsStatusKilledFor(app1, task1_2)

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      verify(f.driver, times(1)).killTask(task1_2.taskId.mesosTaskId)
      verifyNoMoreInteractions(f.driver)
    }
    finally {
      f.system.shutdown()
    }
  }

  class Fixture {
    val tracker: TaskTracker = mock[TaskTracker]
    val queue: LaunchQueue = mock[LaunchQueue]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val storage: StorageProvider = mock[StorageProvider]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val config: UpgradeConfig = mock[UpgradeConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    implicit val system = ActorSystem("TestSystem")
    config.killBatchSize returns 100
    config.killBatchCycle returns 10.seconds

    def driverKillSendsStatusKilledFor(app: AppDefinition, task: Task): Unit = {
      driver.killTask(task.taskId.mesosTaskId) answers { args =>
        system.eventStream.publish(MesosStatusUpdateEvent(
          slaveId = "", taskId = task.taskId, taskStatus = "TASK_KILLED", message = "", appId = app.id, host = "",
          ipAddresses = None, ports = Nil, version = app.version.toString))
        Status.DRIVER_RUNNING
      }
    }

    def deploymentActor(manager: ActorRef, receiver: ActorRef, plan: DeploymentPlan) = TestActorRef(
      DeploymentActor.props(
        manager,
        receiver,
        driver,
        scheduler,
        plan,
        tracker,
        queue,
        storage,
        hcManager,
        system.eventStream,
        readinessCheckExecutor,
        config
      )
    )

  }
}
