package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ ActorContext, ActorRef, ActorSystem, Cancellable, Props, Terminated }
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskCount
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedTaskOps
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.TaskLaunchRejected
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import mesosphere.marathon.tasks.TaskFactory
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, Protos, SameAsSeq }
import org.mockito
import org.mockito.Mockito
import org.scalatest.GivenWhenThen

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
class AppTaskLauncherActorTest extends MarathonSpec with GivenWhenThen {
  import org.mockito.{ Matchers => m }

  test("Initial population of task list from taskTracker with one task") {
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable(marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

    val counts = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

    assert(counts.tasksLaunchedOrRunning == 1)

    assert(!counts.waiting)
    assert(counts.taskLaunchesInFlight == 0)
    assert(counts.tasksLeftToLaunch == 0)

    Mockito.verify(taskTracker).appTasksSync(app.id)
  }

  test("Upgrading an app updates app definition in actor and requeries backoff") {
    Given("an entry for an app")
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable(marathonTask))
    val launcherRef = createLauncherRef(instances = 3)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(app, clock.now()))
    val counts = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]
    assert(counts.tasksLaunchedOrRunning == 1)
    Mockito.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = app.copy(cmd = Some("new command"))
    launcherRef ! AppTaskLauncherActor.AddTasks(upgradedApp, 1)

    Then("the actor requeries the backoff delay")
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    val newDelay: Timestamp = clock.now() + 5.seconds
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, newDelay))
    val counts2 = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]
    assert(counts2.backOffUntil == newDelay)

    And("the actor knows the new app definition")
    assert(counts2.app == upgradedApp)
    And("resets the task to launch according to the new add command")
    assert(counts2.tasksLeftToLaunch == 1)

    And("removes its offer subscription because of the backoff delay")
    Mockito.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(taskTracker)
  }

  test("Upgrading an app updates reregisters the offerMatcher at the manager") {
    Given("an entry for an app")
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable(marathonTask))
    val launcherRef = createLauncherRef(instances = 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(app, clock.now()))
    val counts = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]
    assert(counts.tasksLaunchedOrRunning == 1)

    // We don't care about interactions until this point
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = app.copy(cmd = Some("new command"))
    launcherRef ! AppTaskLauncherActor.AddTasks(upgradedApp, 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, clock.now()))

    // wait for message being processed
    Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

    Then("the actor reregisters itself for at the offerMatcher")
    val inOrder = Mockito.inOrder(offerMatcherManager)
    inOrder.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    inOrder.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(taskTracker)
  }

  test("Process task launch") {
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable.empty[MarathonTask])
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskFactory.newTask(m.any(), m.any(), m.any())).thenReturn(Some(CreatedTask(task, marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedTaskOps]

    val counts = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

    assert(counts.tasksLaunchedOrRunning == 0)

    assert(counts.waiting)
    assert(counts.taskLaunchesInFlight == 1)
    assert(counts.tasksLeftToLaunch == 0)

    Mockito.verify(taskTracker).appTasksSync(app.id)
    Mockito.verify(taskFactory).newTask(m.eq(app), m.eq(offer), m.argThat(SameAsSeq(Seq.empty)))
  }

  test("Wait for inflight task launches on stop") {
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable.empty[MarathonTask])
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskFactory.newTask(m.any(), m.any(), m.any())).thenReturn(Some(CreatedTask(task, marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

    val matched =
      Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedTaskOps]

    val testProbe = TestProbe()
    testProbe.watch(launcherRef)

    launcherRef ! AppTaskLauncherActor.Stop
    Await.result(launcherRef ? "waitingForInFlight", 3.seconds)
    matched.tasks.foreach(_.reject("stuff"))
    testProbe.expectMsgClass(classOf[Terminated])

    Mockito.verify(taskTracker).appTasksSync(app.id)
    Mockito.verify(taskFactory).newTask(m.eq(app), m.eq(offer), m.argThat(SameAsSeq(Seq.empty)))
  }

  test("Process task launch reject") {
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable.empty[MarathonTask])
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskFactory.newTask(m.any(), m.any(), m.any())).thenReturn(Some(CreatedTask(task, marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedTaskOps]
    matchedTasks.tasks.foreach(_.reject("stuff"))

    val counts = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

    assert(counts.tasksLaunchedOrRunning == 0)

    assert(counts.waiting)
    assert(counts.taskLaunchesInFlight == 0)
    assert(counts.tasksLeftToLaunch == 1)

    Mockito.verify(taskTracker).appTasksSync(app.id)
    Mockito.verify(taskFactory).newTask(m.eq(app), m.eq(offer), m.argThat(SameAsSeq(Seq.empty)))
  }

  test("Process task launch timeout") {
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable.empty[MarathonTask])
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskFactory.newTask(m.any(), m.any(), m.any())).thenReturn(Some(CreatedTask(task, marathonTask)))

    var scheduleCalled = false
    val props = Props(
      new AppTaskLauncherActor(
        launchQueueConfig,
        offerMatcherManager, clock, taskFactory,
        maybeOfferReviver = None,
        taskTracker, rateLimiterActor.ref,
        app, tasksToLaunch = 1
      ) {
        override protected def scheduleTaskLaunchTimeout(
          context: ActorContext, message: TaskLaunchRejected): Cancellable = {
          scheduleCalled = true
          mock[Cancellable]
        }
      }
    )
    val launcherRef = actorSystem.actorOf(props, "launcher")

    launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedTaskOps]

    // just make sure that prior messages have been processed, will not launch further tasks

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedTaskOps]

    assert(scheduleCalled)

    Mockito.verify(taskTracker).appTasksSync(app.id)
    Mockito.verify(taskFactory).newTask(m.eq(app), m.eq(offer), m.argThat(SameAsSeq(Seq.empty)))
  }

  test("Process task launch accept") {
    Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable.empty[MarathonTask])
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskFactory.newTask(m.any(), m.any(), m.any())).thenReturn(Some(CreatedTask(task, marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedTaskOps]
    matchedTasks.tasks.foreach(_.accept())

    val counts = Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

    assert(counts.tasksLaunchedOrRunning == 1)
    assert(!counts.waiting)
    assert(counts.taskLaunchesInFlight == 0)
    assert(counts.tasksLeftToLaunch == 0)

    Mockito.verify(taskTracker).appTasksSync(app.id)
    Mockito.verify(taskFactory).newTask(m.eq(app), m.eq(offer), m.argThat(SameAsSeq(Seq.empty)))
  }

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.finished,
      TaskStatusUpdateTestHelper.lost,
      TaskStatusUpdateTestHelper.killed,
      TaskStatusUpdateTestHelper.error
    )
  ) {
    test(s"Remove terminated task (${update.wrapped.status.getClass.getSimpleName})") {
      Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable(marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

      // wait for startup
      Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

      // task status update
      val counts = Await.result(
        launcherRef ? update.withTaskId(marathonTask.getId).wrapped,
        3.seconds
      ).asInstanceOf[QueuedTaskCount]

      assert(counts.tasksLaunchedOrRunning == 0)

      assert(!counts.waiting)
      assert(counts.taskLaunchesInFlight == 0)
      assert(counts.tasksLeftToLaunch == 0)

      Mockito.verify(taskTracker).appTasksSync(app.id)
    }
  }

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.finished,
      TaskStatusUpdateTestHelper.lost,
      TaskStatusUpdateTestHelper.killed,
      TaskStatusUpdateTestHelper.error
    )
  ) {
    test(s"Revive offers if task with constraints terminates (${update.wrapped.status.getClass.getSimpleName})") {
      Given("an actor for an app with constraints and one task")
      val constraint = Protos.Constraint
        .newBuilder()
        .setField("test")
        .setOperator(Protos.Constraint.Operator.CLUSTER)
        .build()
      val appWithConstraints = app.copy(constraints = Set(constraint))
      Mockito.when(taskTracker.appTasksSync(appWithConstraints.id)).thenReturn(Set(marathonTask))

      val launcherRef = createLauncherRef(instances = 0, appToLaunch = appWithConstraints)
      launcherRef ! RateLimiterActor.DelayUpdate(appWithConstraints, clock.now())

      And("that has succesfully started up")
      Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

      When("we get a status update about a terminated task")
      Await.result(launcherRef ? update.withTaskId(marathonTask.getId).wrapped, 3.seconds).asInstanceOf[QueuedTaskCount]

      Then("reviveOffers has been called")
      Mockito.verify(offerReviver).reviveOffers()

      And("the task tracker as well")
      Mockito.verify(taskTracker).appTasksSync(appWithConstraints.id)
    }
  }

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.staging,
      TaskStatusUpdateTestHelper.running
    )
  ) {
    test(s"DO NOT REMOVE running task (${update.wrapped.status.getClass.getSimpleName})") {
      Mockito.when(taskTracker.appTasksSync(app.id)).thenReturn(Iterable(marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(app, clock.now())

      // wait for startup
      Await.result(launcherRef ? AppTaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedTaskCount]

      // task status update
      val counts = Await.result(
        launcherRef ? update.withTaskId(marathonTask.getId).wrapped,
        3.seconds
      ).asInstanceOf[QueuedTaskCount]

      assert(counts.tasksLaunchedOrRunning == 1)

      assert(!counts.waiting)
      assert(counts.taskLaunchesInFlight == 0)
      assert(counts.tasksLeftToLaunch == 0)

      Mockito.verify(taskTracker).appTasksSync(app.id)
    }
  }

  private[this] val app = AppDefinition(id = PathId("testapp"))
  private[this] val task = MarathonTestHelper.makeOneCPUTask("task").build()
  private[this] val marathonTask = MarathonTask.newBuilder().setId(task.getTaskId.getValue).build()

  private[this] implicit val timeout: Timeout = 3.seconds
  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var launchQueueConfig: LaunchQueueConfig = _
  private[this] var offerMatcherManager: OfferMatcherManager = _
  private[this] var clock: ConstantClock = _
  private[this] var taskFactory: TaskFactory = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var offerReviver: OfferReviver = _
  private[this] var rateLimiterActor: TestProbe = _

  private[this] def createLauncherRef(instances: Int, appToLaunch: AppDefinition = app): ActorRef = {
    val props = AppTaskLauncherActor.props(
      launchQueueConfig,
      offerMatcherManager, clock, taskFactory,
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
    launchQueueConfig = new LaunchQueueConfig {}
    launchQueueConfig.afterInit()
    clock = ConstantClock()
    taskFactory = mock[TaskFactory]
    taskTracker = mock[TaskTracker]
    offerReviver = mock[OfferReviver]
    rateLimiterActor = TestProbe()
  }

  after {
    // we are not interested in these. We check for these in LaunchQueueModuleTest
    // Mockito.verifyNoMoreInteractions(offerMatcherManager)
    Mockito.verifyNoMoreInteractions(taskFactory)
    Mockito.verifyNoMoreInteractions(taskTracker)

    actorSystem.shutdown()
    actorSystem.awaitTermination()

  }
}
