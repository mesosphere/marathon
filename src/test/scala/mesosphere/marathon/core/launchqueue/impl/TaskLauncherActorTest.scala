package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ ActorContext, ActorRef, ActorSystem, Cancellable, Props, Terminated }
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.launcher.TaskOpFactory
import mesosphere.marathon.core.launcher.impl.TaskOpFactoryHelper
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedTaskOps
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.TaskOpRejected
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.{ MarathonTaskStatus, MarathonTaskStatusMapping }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, Protos }
import org.mockito
import org.mockito.{ ArgumentCaptor, Mockito }
import org.scalatest.GivenWhenThen
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Also check LaunchQueueModuleTest which tests the interplay of the AppTaskLauncherActor
  * with the queue.
  *
  * In this class we specifically test:
  *
  * * tracking task status
  * * timeout for task launching feedback
  */
class TaskLauncherActorTest extends MarathonSpec with GivenWhenThen {
  import org.mockito.{ Matchers => m }

  test("Initial population of task list from taskTracker with one task") {
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    assert(counts.finalTaskCount == 1)
    assert(!counts.inProgress)
    assert(counts.tasksLeftToLaunch == 0)

    Mockito.verify(taskTracker).tasksByAppSync
  }

  test("Upgrading an app updates app definition in actor and requeries backoff") {
    Given("an entry for an app")
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))
    val launcherRef = createLauncherRef(instances = 3)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]
    assert(counts.tasksLeftToLaunch == 3)
    Mockito.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = f.app.copy(cmd = Some("new command"))
    launcherRef ! TaskLauncherActor.AddTasks(upgradedApp, 1)

    Then("the actor requeries the backoff delay")
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    val newDelay: Timestamp = clock.now() + 5.seconds
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, newDelay))
    val counts2 = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]
    assert(counts2.backOffUntil == newDelay)

    And("the actor knows the new app definition")
    assert(counts2.runSpec == upgradedApp)
    And("resets the task to launch according to the new add command")
    assert(counts2.tasksLeftToLaunch == 1)

    And("removes its offer subscription because of the backoff delay")
    Mockito.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(taskTracker)
  }

  test("Upgrading an app updates reregisters the offerMatcher at the manager") {
    Given("an entry for an app")
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))
    val launcherRef = createLauncherRef(instances = 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]
    assert(counts.tasksLeftToLaunch == 1)

    // We don't care about interactions until this point
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = f.app.copy(cmd = Some("new command"))
    launcherRef ! TaskLauncherActor.AddTasks(upgradedApp, 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, clock.now()))

    // wait for message being processed
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    Then("the actor reregisters itself for at the offerMatcher")
    val inOrder = Mockito.inOrder(offerMatcherManager)
    inOrder.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    inOrder.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(taskTracker)
  }

  test("Process task launch") {
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedTaskOps]

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    assert(counts.finalTaskCount == 2)
    assert(counts.inProgress)
    assert(counts.tasksLeftToLaunch == 0)

    Mockito.verify(taskTracker).tasksByAppSync
    val matchRequest = TaskOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Don't pass the task factory lost tasks when asking for new tasks") {
    import mesosphere.marathon.Protos.Constraint.Operator

    val uniqueConstraint = Protos.Constraint.newBuilder
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .setValue("")
      .build
    val constraintApp: AppDefinition = f.app.copy(constraints = Set(uniqueConstraint))
    val offer = MarathonTestHelper.makeBasicOffer().build()

    val lostTask = MarathonTestHelper.mininimalLostTask(f.app.id)

    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(lostTask))
    val captor = ArgumentCaptor.forClass(classOf[TaskOpFactory.Request])
    // we're only interested in capturing the argument, so return value doesn't matte
    Mockito.when(taskOpFactory.buildTaskOp(captor.capture())).thenReturn(None)

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedTaskOps]
    Mockito.verify(taskTracker).tasksByAppSync
    Mockito.verify(taskOpFactory).buildTaskOp(m.any())
    assert(captor.getValue.taskMap.isEmpty)
  }

  test("Wait for inflight task launches on stop") {
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matched =
      Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedTaskOps]

    val testProbe = TestProbe()
    testProbe.watch(launcherRef)

    launcherRef ! TaskLauncherActor.Stop
    Await.result(launcherRef ? "waitingForInFlight", 3.seconds)
    matched.opsWithSource.foreach(_.reject("stuff"))
    testProbe.expectMsgClass(classOf[Terminated])

    Mockito.verify(taskTracker).tasksByAppSync
    val matchRequest = TaskOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Process task launch reject") {
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedTaskOps]
    matchedTasks.opsWithSource.foreach(_.reject("stuff"))

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    assert(counts.tasksLeftToLaunch == 1)

    assert(counts.inProgress)
    assert(counts.finalTaskCount == 1)
    assert(counts.tasksLeftToLaunch == 1)

    Mockito.verify(taskTracker).tasksByAppSync
    val matchRequest = TaskOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Process task launch timeout") {
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    var scheduleCalled = false
    val props = Props(
      new TaskLauncherActor(
        launchQueueConfig,
        offerMatcherManager, clock, taskOpFactory,
        maybeOfferReviver = None,
        taskTracker, rateLimiterActor.ref,
        f.app, tasksToLaunch = 1
      ) {
        override protected def scheduleTaskOperationTimeout(
          context: ActorContext, message: TaskOpRejected): Cancellable = {
          scheduleCalled = true
          mock[Cancellable]
        }
      }
    )
    val launcherRef = actorSystem.actorOf(props, "launcher")

    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedTaskOps]

    // just make sure that prior messages have been processed, will not launch further tasks

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedTaskOps]

    assert(scheduleCalled)

    Mockito.verify(taskTracker).tasksByAppSync
    val matchRequest = TaskOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Process task launch accept") {
    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedTaskOps]
    matchedTasks.opsWithSource.foreach(_.accept())

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    assert(counts.finalTaskCount == 1)
    assert(!counts.inProgress)
    assert(counts.tasksLeftToLaunch == 0)
    assert(counts.tasksLeftToLaunch == 0)

    Mockito.verify(taskTracker).tasksByAppSync
    val matchRequest = TaskOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Expunged task is removed from counts") {
    val update = TaskStatusUpdateTestHelper.finished(f.marathonTask).wrapped
    val expectedCounts = QueuedTaskInfo(f.app, inProgress = false, 0, 0, 0, Timestamp(0))

    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    // wait for startup
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    // task status update
    val counts = Await.result(launcherRef ? update, 3.seconds).asInstanceOf[QueuedTaskInfo]

    assert(counts.tasksLeftToLaunch == expectedCounts.tasksLeftToLaunch)
    assert(counts.finalTaskCount == expectedCounts.finalTaskCount)

    Mockito.verify(taskTracker).tasksByAppSync
  }

  for (
    update <- MarathonTaskStatusMapping.Gone.toSeq.map(reason => TaskStatusUpdateTestHelper.lost(reason, f.marathonTask))
      .union(Seq(
        TaskStatusUpdateTestHelper.finished(f.marathonTask),
        TaskStatusUpdateTestHelper.killed(f.marathonTask),
        TaskStatusUpdateTestHelper.error(f.marathonTask)))
  ) {
    test(s"Terminated task (${update.simpleName} with ${update.reason} is removed") {
      Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

      // task status update
      val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedTaskInfo]

      assert(counts.tasksLost == 0)

      assert(!counts.inProgress)
      assert(counts.tasksLeftToLaunch == 0)

      Mockito.verify(taskTracker, Mockito.timeout(8000)).tasksByAppSync
    }
  }

  val log = LoggerFactory.getLogger(getClass)
  for (
    update <- MarathonTaskStatusMapping.Unreachable.map(r => TaskStatusUpdateTestHelper.lost(r, f.marathonTask))
  ) {
    test(s"TemporarilyUnreachable task (${update.simpleName} with ${update.reason} is NOT removed") {
      Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

      // task status update
      val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedTaskInfo]
      assert(!counts.inProgress)
      assert(counts.finalTaskCount == 1)
      assert(counts.tasksLost == 1)
      assert(counts.tasksLeftToLaunch == 0)

      Mockito.verify(taskTracker).tasksByAppSync
    }
  }

  test("Updated task is reflected in counts") {
    val update = TaskStatusUpdateTestHelper.runningHealthy(f.marathonTask)
    val expectedCounts = QueuedTaskInfo(f.app, inProgress = false, 0, 1, 0, Timestamp(0))

    Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    // wait for startup
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

    // task status update
    val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedTaskInfo]

    assert(counts.tasksLeftToLaunch == expectedCounts.tasksLeftToLaunch)
    assert(counts.finalTaskCount == expectedCounts.finalTaskCount)

    Mockito.verify(taskTracker).tasksByAppSync
  }

  for (
    update <- MarathonTaskStatusMapping.Gone.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r, f.marathonTask))
      .union(Seq(
        TaskStatusUpdateTestHelper.finished(f.marathonTask),
        TaskStatusUpdateTestHelper.killed(f.marathonTask),
        TaskStatusUpdateTestHelper.error(f.marathonTask)))
  ) {
    test(s"Revive offers if task with constraints terminates (${update.simpleName} with ${update.reason})") {
      Given("an actor for an app with constraints and one task")
      val constraint = Protos.Constraint
        .newBuilder()
        .setField("test")
        .setOperator(Protos.Constraint.Operator.CLUSTER)
        .build()
      val appWithConstraints = f.app.copy(constraints = Set(constraint))
      Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0, appToLaunch = appWithConstraints)
      launcherRef ! RateLimiterActor.DelayUpdate(appWithConstraints, clock.now())

      And("that has successfully started up")
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

      When("we get a status update about a terminated task")
      val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedTaskInfo]

      Then("reviveOffers has been called")
      Mockito.verify(offerReviver).reviveOffers()

      And("the task tracker as well")
      Mockito.verify(taskTracker).tasksByAppSync
    }
  }

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.staging(f.marathonTask),
      TaskStatusUpdateTestHelper.running(f.marathonTask)
    )
  ) {
    test(s"DO NOT REMOVE running task (${update.simpleName})") {
      Mockito.when(taskTracker.tasksByAppSync).thenReturn(TaskTracker.TasksByApp.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskInfo]

      // task status update
      val counts = Await.result(
        launcherRef ? update.wrapped,
        3.seconds
      ).asInstanceOf[QueuedTaskInfo]

      assert(counts.finalTaskCount == 1)
      assert(!counts.inProgress)
      assert(counts.tasksLeftToLaunch == 0)

      Mockito.verify(taskTracker).tasksByAppSync
    }
  }

  object f {
    import org.apache.mesos.{ Protos => Mesos }
    val launch = new TaskOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task.LaunchedEphemeral)
    val app = AppDefinition(id = PathId("/testapp"))
    val taskId = Task.Id.forRunSpec(app.id)
    val task = MarathonTestHelper.makeOneCPUTask(taskId.idString).build()
    val marathonTask = MarathonTestHelper.mininimalTask(task.getTaskId.getValue).copy(
      runSpecVersion = app.version, status = Task.Status(app.version, None, None, taskStatus = MarathonTaskStatus.Running), hostPorts = Seq.empty)
  }

  private[this] implicit val timeout: Timeout = 3.seconds
  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var launchQueueConfig: LaunchQueueConfig = _
  private[this] var offerMatcherManager: OfferMatcherManager = _
  private[this] var clock: ConstantClock = _
  private[this] var taskOpFactory: TaskOpFactory = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var offerReviver: OfferReviver = _
  private[this] var rateLimiterActor: TestProbe = _

  private[this] def createLauncherRef(instances: Int, appToLaunch: AppDefinition = f.app): ActorRef = {
    val props = TaskLauncherActor.props(
      launchQueueConfig,
      offerMatcherManager, clock, taskOpFactory,
      maybeOfferReviver = Some(offerReviver),
      taskTracker, rateLimiterActor.ref) _
    actorSystem.actorOf(
      props(appToLaunch, instances),
      "launcher"
    )
  }

  before {
    actorSystem = ActorSystem()
    offerMatcherManager = mock[OfferMatcherManager]
    launchQueueConfig = new LaunchQueueConfig {
      verify()
    }
    clock = ConstantClock()
    taskOpFactory = mock[TaskOpFactory]
    taskTracker = mock[TaskTracker]
    offerReviver = mock[OfferReviver]
    rateLimiterActor = TestProbe()
  }

  after {
    // we are not interested in these. We check for these in LaunchQueueModuleTest
    // Mockito.verifyNoMoreInteractions(offerMatcherManager)
    Mockito.verifyNoMoreInteractions(taskOpFactory)
    //    Mockito.verifyNoMoreInteractions(taskTracker)
    Await.result(actorSystem.terminate(), Duration.Inf)
  }
}
