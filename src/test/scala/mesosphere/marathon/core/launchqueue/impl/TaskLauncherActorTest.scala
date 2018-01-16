package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{ ActorContext, ActorRef, ActorSystem, Cancellable, Props, Terminated }
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.{ InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.InstanceOpRejected
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.TaskConditionMapping
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, UnreachableEnabled }
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import org.mockito
import org.mockito.{ ArgumentCaptor, Mockito }
import org.scalatest.GivenWhenThen

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

  test("Initial population of task list from instanceTracker with one task") {
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.finalInstanceCount == 1)
    assert(!counts.inProgress)
    assert(counts.instancesLeftToLaunch == 0)

    Mockito.verify(instanceTracker).instancesBySpecSync
  }

  test("Upgrading an app updates app definition in actor and requeries backoff") {
    Given("an entry for an app")
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))
    val launcherRef = createLauncherRef(instances = 3)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
    assert(counts.instancesLeftToLaunch == 3)
    Mockito.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = f.app.copy(cmd = Some("new command"))
    launcherRef ! TaskLauncherActor.AddInstances(upgradedApp, 1)

    Then("the actor requeries the backoff delay")
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    val newDelay: Timestamp = clock.now() + 5.seconds
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, newDelay))
    val counts2 = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
    assert(counts2.backOffUntil == newDelay)

    And("the actor knows the new app definition")
    assert(counts2.runSpec == upgradedApp)
    And("resets the task to launch according to the new add command")
    assert(counts2.instancesLeftToLaunch == 1)

    And("removes its offer subscription because of the backoff delay")
    Mockito.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(instanceTracker)
  }

  test("Upgrading an app updates reregisters the offerMatcher at the manager") {
    Given("an entry for an app")
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))
    val launcherRef = createLauncherRef(instances = 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
    assert(counts.instancesLeftToLaunch == 1)

    // We don't care about interactions until this point
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = f.app.copy(cmd = Some("new command"))
    launcherRef ! TaskLauncherActor.AddInstances(upgradedApp, 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, clock.now()))

    // wait for message being processed
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    Then("the actor reregisters itself for at the offerMatcher")
    val inOrder = Mockito.inOrder(offerMatcherManager)
    inOrder.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    inOrder.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(instanceTracker)
  }

  test("Process task launch") {
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedInstanceOps]

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.finalInstanceCount == 2)
    assert(counts.inProgress)
    assert(counts.instancesLeftToLaunch == 0)

    Mockito.verify(instanceTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Map.empty, additionalLaunches = 1)
    Mockito.verify(instanceOpFactory).matchOfferRequest(matchRequest)
  }

  test("Don't pass the task factory lost tasks when asking for new tasks") {
    import mesosphere.marathon.Protos.Constraint.Operator

    val uniqueConstraint = Protos.Constraint.newBuilder
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .setValue("")
      .build
    val unreachableStrategy = UnreachableEnabled(5.minutes, 10.minutes)
    val constraintApp: AppDefinition = f.app.copy(constraints = Set(uniqueConstraint), unreachableStrategy = unreachableStrategy)
    val offer = MarathonTestHelper.makeBasicOffer().build()

    val lostInstance = TestInstanceBuilder.newBuilder(f.app.id).addTaskUnreachable(unreachableStrategy = unreachableStrategy).getInstance()

    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance))
    val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
    // we're only interested in capturing the argument, so return value doesn't matter
    Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

    val launcherRef = createLauncherRef(instances = 1, constraintApp)
    launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedInstanceOps]

    Mockito.verify(instanceTracker).instancesBySpecSync
    Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
    assert(captor.getValue.instanceMap.isEmpty)
  }

  test("Restart a replacement task for an unreachable task with default unreachableStrategy instantly") {
    import mesosphere.marathon.Protos.Constraint.Operator
    val uniqueConstraint = Protos.Constraint.newBuilder
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .setValue("")
      .build
    val constraintApp: AppDefinition = f.app.copy(constraints = Set(uniqueConstraint))
    val offer = MarathonTestHelper.makeBasicOffer().build()

    val lostInstance = TestInstanceBuilder.newBuilder(f.app.id).addTaskUnreachable().getInstance()

    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance))
    val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
    // we're only interested in capturing the argument, so return value doesn't matter
    Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

    val launcherRef = createLauncherRef(instances = 1, constraintApp)
    launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds).asInstanceOf[MatchedInstanceOps]
    Mockito.verify(instanceTracker).instancesBySpecSync
    Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
    assert(captor.getValue.instanceMap.size == 1) // we should have one replacement task scheduled already
  }

  test("Wait for inflight task launches on stop") {
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matched =
      Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedInstanceOps]

    val testProbe = TestProbe()
    testProbe.watch(launcherRef)

    launcherRef ! TaskLauncherActor.Stop
    Await.result(launcherRef ? "waitingForInFlight", 3.seconds)
    matched.opsWithSource.foreach(_.reject("stuff"))
    testProbe.expectMsgClass(classOf[Terminated])

    Mockito.verify(instanceTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Map.empty, additionalLaunches = 1)
    Mockito.verify(instanceOpFactory).matchOfferRequest(matchRequest)
  }

  test("Process task launch reject") {
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedInstanceOps]
    matchedTasks.opsWithSource.foreach(_.reject("stuff"))

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.instancesLeftToLaunch == 1)

    assert(counts.inProgress)
    assert(counts.finalInstanceCount == 1)
    assert(counts.instancesLeftToLaunch == 1)

    Mockito.verify(instanceTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Map.empty, additionalLaunches = 1)
    Mockito.verify(instanceOpFactory).matchOfferRequest(matchRequest)
  }

  test("Process task launch timeout") {
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

    var scheduleCalled = false
    val props = Props(
      new TaskLauncherActor(
        launchQueueConfig,
        offerMatcherManager, clock, instanceOpFactory,
        maybeOfferReviver = None,
        instanceTracker, rateLimiterActor.ref, offerMatchStatisticsActor.ref,
        f.app, instancesToLaunch = 1
      ) {
        override protected def scheduleTaskOperationTimeout(
          context: ActorContext, message: InstanceOpRejected): Cancellable = {
          scheduleCalled = true
          mock[Cancellable]
        }
      }
    )
    val launcherRef = actorSystem.actorOf(props, "launcher")

    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedInstanceOps]

    // just make sure that prior messages have been processed, will not launch further tasks

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedInstanceOps]

    assert(scheduleCalled)

    Mockito.verify(instanceTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Map.empty, additionalLaunches = 1)
    Mockito.verify(instanceOpFactory).matchOfferRequest(matchRequest)
  }

  test("Process task launch accept") {
    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedInstanceOps]
    matchedTasks.opsWithSource.foreach(_.accept())

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.finalInstanceCount == 1)
    assert(!counts.inProgress)
    assert(counts.instancesLeftToLaunch == 0)
    assert(counts.instancesLeftToLaunch == 0)

    Mockito.verify(instanceTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Map.empty, additionalLaunches = 1)
    Mockito.verify(instanceOpFactory).matchOfferRequest(matchRequest)
  }

  test("Expunged task is removed from counts") {
    val update = TaskStatusUpdateTestHelper.finished(f.marathonInstance).wrapped
    val expectedCounts = QueuedInstanceInfo(f.app, inProgress = false, 0, 0, Timestamp(0), Timestamp(0))

    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    // wait for startup
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    // task status update
    val counts = sendUpdate(launcherRef, update)

    assert(counts.instancesLeftToLaunch == expectedCounts.instancesLeftToLaunch)
    assert(counts.finalInstanceCount == expectedCounts.finalInstanceCount)

    Mockito.verify(instanceTracker).instancesBySpecSync
  }

  for (
    update <- TaskConditionMapping.Gone.toSeq.map(reason => TaskStatusUpdateTestHelper.lost(reason, f.marathonInstance))
      .union(Seq(
        TaskStatusUpdateTestHelper.finished(f.marathonInstance),
        TaskStatusUpdateTestHelper.killed(f.marathonInstance),
        TaskStatusUpdateTestHelper.error(f.marathonInstance)))
  ) {
    test(s"Terminated task (${update.simpleName} with ${update.reason} is removed") {
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      // task status update
      val counts = sendUpdate(launcherRef, update.wrapped)

      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(instanceTracker, Mockito.timeout(8000)).instancesBySpecSync
    }
  }

  for (
    reason <- TaskConditionMapping.Unreachable
  ) {
    test(s"TemporarilyUnreachable task ($reason) is NOT removed") {
      val update = TaskStatusUpdateTestHelper.lost(reason, f.marathonInstance, timestamp = clock.now())
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      // task status update
      val counts = sendUpdate(launcherRef, update.wrapped)
      assert(!counts.inProgress)
      assert(counts.finalInstanceCount == 1)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(instanceTracker).instancesBySpecSync
    }
  }

  test("Updated task is reflected in counts") {
    val update = TaskStatusUpdateTestHelper.runningHealthy(f.marathonInstance)
    val expectedCounts = QueuedInstanceInfo(f.app, inProgress = false, 0, 1, Timestamp(0), Timestamp(0))

    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    // wait for startup
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    // task status update
    val counts = sendUpdate(launcherRef, update.wrapped)

    assert(counts.instancesLeftToLaunch == expectedCounts.instancesLeftToLaunch)
    assert(counts.finalInstanceCount == expectedCounts.finalInstanceCount)

    Mockito.verify(instanceTracker).instancesBySpecSync
  }

  for (
    update <- TaskConditionMapping.Gone.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r, f.marathonInstance))
      .union(Seq(
        TaskStatusUpdateTestHelper.finished(f.marathonInstance),
        TaskStatusUpdateTestHelper.killed(f.marathonInstance),
        TaskStatusUpdateTestHelper.error(f.marathonInstance)))
  ) {
    test(s"Revive offers if task with constraints terminates (${update.simpleName} with ${update.reason})") {
      Given("an actor for an app with constraints and one task")
      val constraint = Protos.Constraint
        .newBuilder()
        .setField("test")
        .setOperator(Protos.Constraint.Operator.CLUSTER)
        .build()
      val appWithConstraints = f.app.copy(constraints = Set(constraint))
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

      val launcherRef = createLauncherRef(instances = 0, appToLaunch = appWithConstraints)
      launcherRef ! RateLimiterActor.DelayUpdate(appWithConstraints, clock.now())

      And("that has successfully started up")
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      When("we get a status update about a terminated task")
      val counts = sendUpdate(launcherRef, update.wrapped)

      Then("reviveOffers has been called")
      Mockito.verify(offerReviver).reviveOffers()

      And("the task tracker as well")
      Mockito.verify(instanceTracker).instancesBySpecSync
    }
  }

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.staging(f.marathonInstance),
      TaskStatusUpdateTestHelper.running(f.marathonInstance)
    )
  ) {
    test(s"DO NOT REMOVE running task (${update.simpleName})") {
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.marathonInstance))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      val lolo = update
      // task status update
      val counts = sendUpdate(launcherRef, update.wrapped)

      assert(counts.finalInstanceCount == 1)
      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(instanceTracker).instancesBySpecSync
    }
  }

  def sendUpdate(launcherRef: ActorRef, update: InstanceChange): QueuedInstanceInfo = {
    launcherRef ! update
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
  }

  object f {
    import org.apache.mesos.{ Protos => Mesos }
    val app = AppDefinition(id = PathId("/testapp"))
    private val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id, version = app.version, now = Timestamp.now())
    val marathonInstance = builder.getInstance()
    val marathonTask: Task.LaunchedEphemeral = builder.pickFirstTask()
    val instanceId = marathonInstance.instanceId
    val task = MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(instanceId, None)).build()
    val opFactory = new InstanceOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task.LaunchedEphemeral, _: Instance)
    val launch = opFactory(task, marathonTask, marathonInstance)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val noMatchResult = OfferMatchResult.NoMatch(app, offer, Seq.empty, Timestamp.now())
    val launchResult = OfferMatchResult.Match(app, offer, launch, Timestamp.now())
  }

  private[this] implicit val timeout: Timeout = 3.seconds
  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var launchQueueConfig: LaunchQueueConfig = _
  private[this] var offerMatcherManager: OfferMatcherManager = _
  private[this] var clock: ConstantClock = _
  private[this] var instanceOpFactory: InstanceOpFactory = _
  private[this] var instanceTracker: InstanceTracker = _
  private[this] var offerReviver: OfferReviver = _
  private[this] var rateLimiterActor: TestProbe = _
  private[this] var offerMatchStatisticsActor: TestProbe = _

  private[this] def createLauncherRef(instances: Int, appToLaunch: AppDefinition = f.app): ActorRef = {
    val props = TaskLauncherActor.props(
      launchQueueConfig,
      offerMatcherManager, clock, instanceOpFactory,
      maybeOfferReviver = Some(offerReviver),
      instanceTracker, rateLimiterActor.ref, offerMatchStatisticsActor.ref) _
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
    instanceOpFactory = mock[InstanceOpFactory]
    instanceTracker = mock[InstanceTracker]
    offerReviver = mock[OfferReviver]
    rateLimiterActor = TestProbe()
    offerMatchStatisticsActor = TestProbe()
  }

  after {
    // we are not interested in these. We check for these in LaunchQueueModuleTest
    // Mockito.verifyNoMoreInteractions(offerMatcherManager)
    Mockito.verifyNoMoreInteractions(instanceOpFactory)
    //    Mockito.verifyNoMoreInteractions(instanceTracker)
    Await.result(actorSystem.terminate(), Duration.Inf)
  }
}
