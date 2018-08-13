package mesosphere.marathon

import akka.Done
import akka.actor.Props
import akka.event.EventStream
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Source}
import akka.testkit._
import mesosphere.AkkaUnitTest
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment._
import mesosphere.marathon.core.deployment.impl.{DeploymentManagerActor, DeploymentManagerDelegate}
import mesosphere.marathon.core.election.{ElectionService, LeadershipTransition}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.history.impl.HistoryActor
import mesosphere.marathon.core.instance.update.InstanceChangedEventsGenerator
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{DeploymentRepository, FrameworkIdRepository, GroupRepository, TaskFailureRepository}
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.stream.Subject
import mesosphere.marathon.test.GroupCreation
import org.apache.mesos.Protos.{Status, TaskStatus}
import org.apache.mesos.SchedulerDriver
import org.mockito
import org.scalatest.concurrent.Eventually

import scala.collection.immutable.Set
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class MarathonSchedulerActorTest extends AkkaUnitTest with ImplicitSender with GroupCreation with Eventually {

  def withFixture[T](overrideActions: Option[SchedulerActions] = None)(testCode: Fixture => T): T = {
    val f = new Fixture(overrideActions)
    try {
      testCode(f)
    } finally {
      f.stopActor()
    }
  }

  "MarathonSchedulerActor" should {
    "RecoversDeploymentsAndReconcilesHealthChecksOnStart" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))
      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      eventually {
        verify(hcManager).reconcile(Seq(app))
      }
      verify(deploymentRepo, times(1)).all()
    }

    "Reconcile orphan instance of unknown app - instance should be killed" in withFixture() { f =>
      val app = AppDefinition(id = "/deleted-app".toPath, instances = 1)
      val orphanedInstance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

      f.groupRepo.root() returns Future.successful(createRootGroup())
      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(orphanedInstance))

      f.leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      f.schedulerActor ! ReconcileTasks

      expectMsg(TasksReconciled)

      eventually {
        verify(f.killService).killInstance(eq(orphanedInstance), any)
      }
    }

    "Terminal tasks should not be submitted in reconciliation" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskUnreachable(containerName = Some("unreachable")).addTaskRunning().addTaskGone(containerName = Some("gone")).getInstance()

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! ReconcileTasks

      expectMsg(TasksReconciled)

      val expectedStatus: java.util.Collection[TaskStatus] = TaskStatusCollector.collectTaskStatusFor(Seq(instance)).asJava
      assert(expectedStatus.size() == 2, "Only non-terminal tasks should be expected to be reconciled")
      eventually {
        driver.reconcileTasks(expectedStatus)
      }
      eventually {
        driver.reconcileTasks(java.util.Arrays.asList())
      }
    }

    "Terminal tasks should not be submitted in reconciliation - Instance with only terminal tasks" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))
      val instance = TestInstanceBuilder.newBuilder(app.id)
        .addTaskError(containerName = Some("error"))
        .addTaskFailed(containerName = Some("failed"))
        .addTaskFinished(containerName = Some("finished"))
        .addTaskKilled(containerName = Some("killed"))
        .addTaskGone(containerName = Some("gone"))
        .addTaskDropped(containerName = Some("dropped"))
        .addTaskUnknown(containerName = Some("unknown"))
        .getInstance()

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! ReconcileTasks

      expectMsg(TasksReconciled)

      verify(driver, once).reconcileTasks(java.util.Arrays.asList())
      noMoreInteractions(driver)
    }

    "Terminal tasks should not be submitted in reconciliation - Instance with all kind of tasks status" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))
      val instance = TestInstanceBuilder.newBuilder(app.id)
        .addTaskError(containerName = Some("error"))
        .addTaskFailed(containerName = Some("failed"))
        .addTaskFinished(containerName = Some("finished"))
        .addTaskKilled(containerName = Some("killed"))
        .addTaskGone(containerName = Some("gone"))
        .addTaskDropped(containerName = Some("dropped"))
        .addTaskUnknown(containerName = Some("unknown"))
        .addTaskKilling(containerName = Some("killing"))
        .addTaskReserved(containerName = Some("reserved"))
        .addTaskRunning(containerName = Some("running"))
        .addTaskStaging(containerName = Some("staging"))
        .addTaskStarting(containerName = Some("starting"))
        .addTaskUnreachable(containerName = Some("unreachable"))
        .getInstance()

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! ReconcileTasks

      expectMsg(TasksReconciled)

      val nonTerminalTasks = instance.tasksMap.values.filter(!_.task.isTerminal)
      assert(nonTerminalTasks.size == 6, "We should have 7 non-terminal tasks")

      val expectedStatus: java.util.Collection[TaskStatus] = TaskStatusCollector.collectTaskStatusFor(Seq(instance)).asJava

      assert(expectedStatus.size() == 5, "We should have 5 task statuses")

      eventually {
        driver.reconcileTasks(expectedStatus)
      }
      eventually {
        driver.reconcileTasks(java.util.Arrays.asList())
      }
    }

    "Created tasks should not be submitted in reconciliation" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))
      val instance = TestInstanceBuilder.newBuilder(app.id)
        .addTaskCreated(containerName = Some("created"))
        .getInstance()

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(instance))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! ReconcileTasks

      expectMsg(TasksReconciled)

      val tasksToReconcile: java.util.Collection[TaskStatus] = TaskStatusCollector.collectTaskStatusFor(Seq(instance)).asJava
      assert(tasksToReconcile.isEmpty, "Created task should not be submited for reconciliation")
    }

    "ScaleApps" in withFixture() { f =>
      import f._
      val app: AppDefinition = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))

      val instances = Seq(TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance())

      instanceTracker.specInstances(mockito.Matchers.eq("nope".toPath))(mockito.Matchers.any[ExecutionContext]) returns Future.successful(instances)
      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! ScaleRunSpecs

      eventually {
        //verify(queue).add(app, 1)
      }
    }

    "ScaleApp" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app-scale".toPath, instances = 1, cmd = Some("sleep"))

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! ScaleRunSpec("/test-app-scale".toPath)

      eventually {
        //verify(queue).add(app, 1)
      }

      expectMsg(RunSpecScaled(app.id))
    }

    "Kill tasks with scaling" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app-kill-with-scale".toPath, instances = 1, cmd = Some("sleep"))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskStaged().getInstance()
      val failedInstance = TaskStatusUpdateTestHelper.failed(instance).updatedInstance
      val events = InstanceChangedEventsGenerator.events(
        failedInstance, task = Some(failedInstance.appTask), now = Timestamp.now(), previousCondition = Some(instance.state.condition))

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! KillTasks(app.id, Seq(instance))

      eventually {
        verify(killService, times(1)).killInstances(any, any)
      }
      events.foreach(system.eventStream.publish)

      expectMsg(TasksKilled(app.id, Seq(instance.instanceId)))

      val mesosStatusUpdateEvent: MesosStatusUpdateEvent = events.collectFirst {
        case event: MesosStatusUpdateEvent => event
      }.getOrElse {
        fail(s"$events did not contain a MesosStatusUpdateEvent")
      }
      val Some(taskFailureEvent) = TaskFailure.FromMesosStatusUpdateEvent(mesosStatusUpdateEvent)

      eventually {
        verify(taskFailureEventRepository, times(1)).store(taskFailureEvent)
      }
      noMoreInteractions(killService)
    }

    "Kill tasks" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = "/test-app".toPath, instances = 1, cmd = Some("sleep"))
      val instanceA = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id).getInstance()

      groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! KillTasks(app.id, Seq(instanceA))

      eventually {
        verify(killService, times(1)).killInstances(any, any)
      }
      system.eventStream.publish(instanceKilled(instanceA))

      expectMsg(TasksKilled(app.id, List(instanceA.instanceId)))

      eventually {
        //verify(queue).add(app, 1)
      }
    }

    "Deployment" in withFixture() { f =>
      import f._
      val app = AppDefinition(
        id = PathId("/foo/app1"),
        cmd = Some("cmd"),
        instances = 2,
        upgradeStrategy = UpgradeStrategy(0.5),
        versionInfo = VersionInfo.forNewConfig(Timestamp(0))
      )
      val probe = TestProbe()
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val appNew = app.copy(
        cmd = Some("cmd new"),
        versionInfo = VersionInfo.forNewConfig(Timestamp(1000))
      )

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(appNew.id -> appNew))))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, Nil, Timestamp.now())

      system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! Deploy(plan)

      expectMsg(DeploymentStarted(plan))

      val answer = probe.expectMsgType[DeploymentSuccess]
      answer.id should be(plan.id)

      system.eventStream.unsubscribe(probe.ref)
    }

    "Deployment resets rate limiter for affected apps" in withFixture() { f =>
      import f._
      val app = AppDefinition(
        id = PathId("/foo/app1"),
        cmd = Some("cmd"),
        instances = 2,
        upgradeStrategy = UpgradeStrategy(0.5),
        versionInfo = VersionInfo.forNewConfig(Timestamp(0))
      )
      val probe = TestProbe()
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))
      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"))))

      val plan = DeploymentPlan("d2", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

      f.queue.purge(app.id) returns Future.successful(Done)

      instanceTracker.specInstances(mockito.Matchers.eq(app.id))(any[ExecutionContext]) returns Future.successful(Seq(instance))
      system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! Deploy(plan)

      expectMsg(DeploymentStarted(plan))

      // TODO: See DeploymentActor.stopRunnable
      // verify(f.queue, timeout(1000)).purge(app.id)
      verify(f.scheduler, timeout(1000)).resetDelay(app.copy(instances = 0))

      system.eventStream.unsubscribe(probe.ref)
    }

    "Deployment fail to acquire lock" in withFixture() { f =>
      import f._
      val app = AppDefinition(
        id = PathId("/foo/app1"),
        cmd = Some("cmd"),
        instances = 2,
        upgradeStrategy = UpgradeStrategy(0.5),
        versionInfo = VersionInfo.forNewConfig(Timestamp(0))
      )
      val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val plan = DeploymentPlan(createRootGroup(), rootGroup, id = Some("d3"))

      groupRepo.root() returns Future.successful(rootGroup)

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted]

      schedulerActor ! Deploy(plan)

      val answer = expectMsgType[MarathonSchedulerActor.DeploymentFailed]

      answer.plan should equal(plan)
      answer.reason.isInstanceOf[AppLockedException] should be(true)
    }

    "Restart deployments after failover" in withFixture() { f =>
      import f._
      val app = AppDefinition(
        id = PathId("/foo/app1"),
        cmd = Some("cmd"),
        instances = 2,
        upgradeStrategy = UpgradeStrategy(0.5),
        versionInfo = VersionInfo.forNewConfig(Timestamp(0))
      )
      val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val plan = DeploymentPlan(createRootGroup(), rootGroup, id = Some("d4"))

      deploymentRepo.delete(any) returns Future.successful(Done)
      deploymentRepo.all() returns Source.single(plan)
      deploymentRepo.store(plan) returns Future.successful(Done)

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! Deploy(plan)

      // This indicates that the deployment is already running,
      // which means it has successfully been restarted
      val answer = expectMsgType[MarathonSchedulerActor.DeploymentFailed]
      answer.plan should equal(plan)
      answer.reason.isInstanceOf[AppLockedException] should be(true)
    }

    "Forced deployment" in withFixture() { f =>
      import f._
      val app = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5))
      val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val plan = DeploymentPlan(createRootGroup(), rootGroup, id = Some("d1"))

      groupRepo.root() returns Future.successful(rootGroup)

      leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted](10.seconds)

      schedulerActor ! Deploy(plan.copy(id = "d2"), force = true)

      expectMsgType[DeploymentStarted]
    }

    "Do not run reconciliation concurrently" in {
      val actions = mock[SchedulerActions]
      withFixture(Some(actions)) { f =>
        import f._

        val reconciliationPromise = Promise[Status]()
        actions.reconcileTasks(any) returns reconciliationPromise.future
        groupRepo.root() returns Future.successful(createRootGroup())

        leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)

        schedulerActor ! MarathonSchedulerActor.ReconcileTasks // linter:ignore
        schedulerActor ! MarathonSchedulerActor.ReconcileTasks // linter:ignore

        reconciliationPromise.success(Status.DRIVER_RUNNING)

        expectMsg(MarathonSchedulerActor.TasksReconciled) // linter:ignore
        expectMsg(MarathonSchedulerActor.TasksReconciled) // linter:ignore

        schedulerActor ! MarathonSchedulerActor.ReconcileTasks
        expectMsg(MarathonSchedulerActor.TasksReconciled)

        verify(actions, times(2)).reconcileTasks(any[SchedulerDriver])
      }
    }

    "Concurrent reconciliation check is not preventing sequential calls" in {
      val actions = mock[SchedulerActions]
      withFixture(Some(actions)) { f =>
        import f._

        actions.reconcileTasks(any) returns Future.successful(Status.DRIVER_RUNNING)
        groupRepo.root() returns Future.successful(createRootGroup())

        leadershipTransitionInput.offer(LeadershipTransition.ElectedAsLeaderAndReady)

        schedulerActor ! MarathonSchedulerActor.ReconcileTasks
        expectMsg(MarathonSchedulerActor.TasksReconciled)

        schedulerActor ! MarathonSchedulerActor.ReconcileTasks
        expectMsg(MarathonSchedulerActor.TasksReconciled)

        schedulerActor ! MarathonSchedulerActor.ReconcileTasks
        expectMsg(MarathonSchedulerActor.TasksReconciled)

        verify(actions, times(3)).reconcileTasks(any[SchedulerDriver])
      }
    }
  }

  class Fixture(overrideActions: Option[SchedulerActions] = None) {
    val groupRepo: GroupRepository = mock[GroupRepository]
    groupRepo.root() returns Future.successful(createRootGroup())

    val deploymentRepo: DeploymentRepository = mock[DeploymentRepository]
    deploymentRepo.store(any) returns Future.successful(Done)
    deploymentRepo.delete(any) returns Future.successful(Done)
    deploymentRepo.all() returns Source.empty

    val hcManager: HealthCheckManager = mock[HealthCheckManager]

    val instanceTracker: InstanceTracker = mock[InstanceTracker]
    instanceTracker.specInstances(any)(any) returns Future.successful(Seq.empty[Instance])
    instanceTracker.specInstancesSync(any) returns Seq.empty[Instance]
    instanceTracker.setGoal(any, any) returns Future.successful(Done)
    instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
    val killService = mock[KillService]
    killService.killInstance(any, any) returns Future.successful(Done)
    killService.killInstances(any, any) returns Future.successful(Done)

    val queue: LaunchQueue = mock[LaunchQueue]
    queue.sync(any) returns Future.successful(Done)

    val scheduler = mock[scheduling.Scheduler]
    scheduler.getInstances(any)(any) returns Future.successful(Seq.empty[Instance])

    val frameworkIdRepo: FrameworkIdRepository = mock[FrameworkIdRepository]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    holder.driver = Some(driver)
    val taskFailureEventRepository: TaskFailureRepository = mock[TaskFailureRepository]
    val (leadershipTransitionInput, leadershipTransitionEvents) = Source.queue[LeadershipTransition](16, OverflowStrategy.fail)
      .toMat(Subject(16, OverflowStrategy.fail))(Keep.both)
      .run
    val electionService: ElectionService = mock[ElectionService]
    val schedulerActions: SchedulerActions = new SchedulerActions(
      groupRepo, hcManager, instanceTracker, queue, new EventStream(system), killService)(system.dispatcher)
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    val historyActorProps: Props = Props(new HistoryActor(system.eventStream, taskFailureEventRepository))

    val conf: DeploymentConfig = mock[DeploymentConfig]
    conf.killBatchCycle returns 1.seconds
    conf.killBatchSize returns 100
    conf.deploymentManagerRequestDuration returns 1.seconds

    val metrics: Metrics = DummyMetrics

    val deploymentManagerActor = system.actorOf(DeploymentManagerActor.props(
      metrics,
      schedulerActions,
      scheduler,
      hcManager,
      system.eventStream,
      readinessCheckExecutor,
      deploymentRepo
    ))

    val deploymentManager = new DeploymentManagerDelegate(conf, deploymentManagerActor)

    val schedulerActor = system.actorOf(
      MarathonSchedulerActor.props(
        groupRepo,
        overrideActions.getOrElse(schedulerActions),
        deploymentManager,
        deploymentRepo,
        historyActorProps,
        hcManager,
        killService,
        queue,
        holder,
        leadershipTransitionEvents,
        system.eventStream
      )
    )

    def instanceKilled(instance: Instance): InstanceChanged = {
      val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed))
      InstanceChanged(instance.instanceId, instance.runSpecVersion, instance.runSpecId, Condition.Killed, instance)
    }

    def stopActor(): Unit = {
      watch(schedulerActor)
      system.stop(schedulerActor)
      expectTerminated(schedulerActor)

      watch(deploymentManagerActor)
      system.stop(deploymentManagerActor)
      expectTerminated(deploymentManagerActor)
    }
  }
}
