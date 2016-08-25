package mesosphere.marathon.upgrade

import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.{ Task, TaskKillServiceMock }
import mesosphere.marathon.core.event.MesosStatusUpdateEvent
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFinished, DeploymentStepInfo }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ verifyNoMoreInteractions, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.Await

// TODO: this is NOT a unit test. the DeploymentActor create child actors that cannot be mocked in the current
// setup which makes the test overly complicated because events etc have to be mocked for these.
// The way forward should be to provide factories that create the child actors with a given context, or
// to use delegates that hide the implementation behind a mockable function call.
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
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(
      app1.id -> app1,
      app2.id -> app2,
      app4.id -> app4))))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val app1New = app1.copy(instances = 1, versionInfo = version2)
    val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), versionInfo = version2)

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(
      app1New.id -> app1New,
      app2New.id -> app2New,
      app3.id -> app3))))

    // setting started at to 0 to make sure this survives
    val task1_1 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app1.id).idString, appVersion = app1.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app1.id).idString, appVersion = app1.version, startedAt = 1000)
    val task2_1 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app2.id).idString, appVersion = app2.version)
    val task3_1 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app3.id).idString, appVersion = app3.version)
    val task4_1 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app4.id).idString, appVersion = app4.version)

    val plan = DeploymentPlan(origGroup, targetGroup)

    when(f.tracker.appTasksLaunchedSync(app1.id)).thenReturn(Set(task1_1, task1_2))
    when(f.tracker.appTasksLaunchedSync(app2.id)).thenReturn(Set(task2_1))
    when(f.tracker.appTasksLaunchedSync(app3.id)).thenReturn(Set(task3_1))
    when(f.tracker.appTasksLaunchedSync(app4.id)).thenReturn(Set(task4_1))

    when(f.queue.add(same(app2New), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        println(invocation.getArguments.toSeq)
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          system.eventStream.publish(MesosStatusUpdateEvent(
            slaveId = "", taskId = Task.Id.forRunSpec(app2New.id), taskStatus = "TASK_RUNNING", message = "",
            appId = app2.id, host = "", ipAddresses = None, ports = Nil, version = app2New.version.toString)
          )
        true
      }
    })

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      verify(f.scheduler).startApp(f.driver, app3.copy(instances = 0))
      println(f.killService.killed.mkString(","))
      f.killService.killed should contain (task1_2.taskId) // killed due to scale down
      f.killService.killed should contain (task2_1.taskId) // killed due to config change
      f.killService.killed should contain (task4_1.taskId) // killed because app4 does not exist anymore
      f.killService.numKilled should be (3)
      verify(f.scheduler).stopApp(app4.copy(instances = 0))
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  test("Restart app") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(appNew.id -> appNew))))

    val task1_1 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString, appVersion = app.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString, appVersion = app.version, startedAt = 1000)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Set(task1_1, task1_2))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(f.queue.count(appNew.id)).thenAnswer(new Answer[Int] {
      override def answer(p1: InvocationOnMock): Int = appNew.instances
    })

    when(f.queue.add(same(appNew), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          f.system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id.forRunSpec(app.id),
            "TASK_RUNNING", "", app.id, "", None, Nil, appNew.version.toString))
        true
      }
    })

    try {

      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      receiverProbe.expectMsg(DeploymentFinished(plan))

      f.killService.killed should contain (task1_1.taskId)
      f.killService.killed should contain (task1_2.taskId)
      verify(f.queue).add(appNew, 2)
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  test("Restart suspended app") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()

    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 0)
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(appNew.id -> appNew))))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable.empty[Task])

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      receiverProbe.expectMsg(DeploymentFinished(plan))
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  test("Scale with tasksToKill") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 3)
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app1.id -> app1))))

    val version2 = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
    val app1New = app1.copy(instances = 2, versionInfo = version2)

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app1New.id -> app1New))))

    val task1_1 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app1.id).idString, appVersion = app1.version, startedAt = 0)
    val task1_2 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app1.id).idString, appVersion = app1.version, startedAt = 500)
    val task1_3 = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app1.id).idString, appVersion = app1.version, startedAt = 1000)

    val plan = DeploymentPlan(original = origGroup, target = targetGroup, toKill = Map(app1.id -> Set(task1_2)))

    when(f.tracker.appTasksLaunchedSync(app1.id)).thenReturn(Set(task1_1, task1_2, task1_3))

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      f.killService.numKilled should be (1)
      f.killService.killed should contain (task1_2.taskId)
      verifyNoMoreInteractions(f.driver)
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  class Fixture {
    implicit val system = ActorSystem("TestSystem")
    val tracker: TaskTracker = mock[TaskTracker]
    val queue: LaunchQueue = mock[LaunchQueue]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val killService = new TaskKillServiceMock(system)
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val storage: StorageProvider = mock[StorageProvider]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val config: UpgradeConfig = mock[UpgradeConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    config.killBatchSize returns 100
    config.killBatchCycle returns 10.seconds

    def deploymentActor(manager: ActorRef, receiver: ActorRef, plan: DeploymentPlan) = TestActorRef(
      DeploymentActor.props(
        manager,
        receiver,
        driver,
        killService,
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
