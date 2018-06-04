package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdated}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.launcher.{InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.TaskConditionMapping
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import org.mockito
import org.mockito.{ArgumentCaptor, Mockito}

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}
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
class TaskLauncherActorTest extends AkkaUnitTest {

  import org.mockito.{Matchers => m}

  def sendUpdate(launcherRef: ActorRef, update: InstanceChange): QueuedInstanceInfo = {
    launcherRef ! update
    (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
  }

  object f {
    import org.apache.mesos.{Protos => Mesos}
    val app = AppDefinition(id = PathId("/testapp"))
    val marathonInstance = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id, version = app.version, now = Timestamp.now()).getInstance()
    val marathonTask: Task = marathonInstance.appTask
    val instanceId = marathonInstance.instanceId
    val task = MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(instanceId, None)).build()
    val opFactory = new InstanceOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task, _: Instance)
    val launch = opFactory(task, marathonTask, marathonInstance)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val noMatchResult = OfferMatchResult.NoMatch(app, offer, Seq.empty, Timestamp.now())
    val launchResult = OfferMatchResult.Match(app, offer, launch, Timestamp.now())
  }

  case class Fixture(
      offerMatcherManager: OfferMatcherManager = mock[OfferMatcherManager],
      launchQueueConfig: LaunchQueueConfig = new LaunchQueueConfig { verify() },
      clock: SettableClock = new SettableClock(),
      instanceOpFactory: InstanceOpFactory = mock[InstanceOpFactory],
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      offerReviver: OfferReviver = mock[OfferReviver],
      rateLimiterActor: TestProbe = TestProbe(),
      offerMatchStatisticsActor: TestProbe = TestProbe(),
      localRegion: () => Option[Region] = () => None) {

    def createLauncherRef(appToLaunch: AppDefinition = f.app): ActorRef = {
      val props = TaskLauncherActor.props(
        launchQueueConfig,
        offerMatcherManager, clock, instanceOpFactory,
        maybeOfferReviver = Some(offerReviver),
        instanceTracker, rateLimiterActor.ref, offerMatchStatisticsActor.ref, localRegion) _
      system.actorOf(props(appToLaunch))
    }

    def verifyClean(): Unit = {
      // we are not interested in these. We check for these in LaunchQueueModuleTest
      // Mockito.verifyNoMoreInteractions(offerMatcherManager)
      Mockito.verifyNoMoreInteractions(instanceOpFactory)
    }
  }

  "TaskLauncherActor" should {
    "Initial population of task list from instanceTracker with one task" in new Fixture {
      instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      assert(counts.finalInstanceCount == 1)
      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)

      verifyClean()
    }

    // This test does not apply to the new task launcher. The number of scheduled instances should not be defined in the
    // task launcher but outside.
    "upgrade an app updates app definition in actor and requeries backoff" ignore new Fixture {
      Given("an entry for an app")
      val instances = Seq(
        f.marathonInstance,
        Instance.Scheduled(f.app, Instance.Id.forRunSpec(f.app.id)),
        Instance.Scheduled(f.app, Instance.Id.forRunSpec(f.app.id)),
        Instance.Scheduled(f.app, Instance.Id.forRunSpec(f.app.id))
      )
      instanceTracker.list(any)(any) returns Future.successful(instances)
      instanceTracker.specInstancesSync(any) returns instances

      val launcherRef = createLauncherRef()
      // TODO(karsten): Schedule 3 instances
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
      rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
      assert(counts.instancesLeftToLaunch == 3)
      Mockito.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
      Mockito.reset(offerMatcherManager)

      When("upgrading the app")
      val upgradedApp = f.app.copy(cmd = Some("new command"))
      launcherRef ! TaskLauncherActor.Sync(upgradedApp, instances) //TaskLauncherActor.AddInstances(upgradedApp, 1)

      Then("the actor requeries the backoff delay")
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
      val newDelay: Timestamp = clock.now() + 5.seconds
      rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, newDelay))
      val counts2 = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
      assert(counts2.backOffUntil == newDelay)

      And("the actor knows the new app definition")
      assert(counts2.runSpec == upgradedApp)
      And("resets the task to launch according to the new add command")
      assert(counts2.instancesLeftToLaunch == 1)

      And("removes its offer subscription because of the backoff delay")
      Mockito.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())

      // We don't care about these:
      Mockito.reset(instanceTracker)
      verifyClean()
    }

    "Upgrading an app updates reregisters the offerMatcher at the manager" in new Fixture {
      Given("an entry for an app")
      val instances = Seq(f.marathonInstance, Instance.Scheduled(f.app))
      instanceTracker.list(any)(any) returns Future.successful(instances)
      val launcherRef = createLauncherRef()
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
      rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
      assert(counts.instancesLeftToLaunch == 1)

      // We don't care about interactions until this point
      Mockito.reset(offerMatcherManager)

      When("upgrading the app")
      val upgradedApp = f.app.copy(cmd = Some("new command"))
      launcherRef ! TaskLauncherActor.Sync(upgradedApp, instances) //TaskLauncherActor.AddInstances(upgradedApp, 1)
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
      rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, clock.now()))

      // wait for message being processed
      (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      Then("the actor reregisters itself for at the offerMatcher")
      val inOrder = Mockito.inOrder(offerMatcherManager)
      inOrder.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())
      inOrder.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())

      // We don't care about these:
      Mockito.reset(instanceTracker)
      verifyClean()
    }

    "Process task launch" in new Fixture {

      Given("a scheduled and a running instance")
      val scheduledInstance = Instance.Scheduled(f.app)
      val instances = Seq(f.marathonInstance, scheduledInstance)
      instanceTracker.list(any)(any) returns Future.successful(instances)
      val offer = MarathonTestHelper.makeBasicOffer().build()
      instanceOpFactory.matchOfferRequest(m.any()) returns f.launchResult

      val launcherRef = createLauncherRef()
      val now = clock.now()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, now)

      When("the launcher receives an offer")
      val promise = Promise[OfferMatchResult]
      launcherRef ! TaskLauncherActor.MatchOffer(offer, promise, instances)

      Then("it is matched")
      promise.future.futureValue

      When("the launcher receives the update for the provisioned instance")
      val provisionedInstance = scheduledInstance.copy(state = Instance.InstanceState(Condition.Provisioned, clock.now(), None, None))
      val update = InstanceUpdated(provisionedInstance, Some(scheduledInstance.state), Seq.empty)
      instanceTracker.specInstancesSync(f.app.id) returns Seq(f.marathonInstance, provisionedInstance)
      val counts = sendUpdate(launcherRef, update)

      Then("there are not instances left to launch")
      assert(counts.finalInstanceCount == 2)
      assert(counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)
    }

    "Don't pass the task factory lost tasks when asking for new tasks" in new Fixture {
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

      instanceTracker.list(any)(any) returns Future.successful(Seq(lostInstance, Instance.Scheduled(f.app)))
      val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
      // we're only interested in capturing the argument, so return value doesn't matter
      Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

      val launcherRef = createLauncherRef(constraintApp)
      launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

      val promise = Promise[OfferMatchResult]
      launcherRef ! TaskLauncherActor.MatchOffer(offer, promise, Seq(lostInstance))
      promise.future.futureValue

      Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
      assert(captor.getValue.instances.isEmpty)
      verifyClean()
    }

    "Restart a replacement task for an unreachable task with default unreachableStrategy instantly" in new Fixture {
      import mesosphere.marathon.Protos.Constraint.Operator

      val uniqueConstraint = Protos.Constraint.newBuilder
        .setField("hostname")
        .setOperator(Operator.UNIQUE)
        .setValue("")
        .build
      val constraintApp: AppDefinition = f.app.copy(constraints = Set(uniqueConstraint))
      val offer = MarathonTestHelper.makeBasicOffer().build()

      val lostInstance = TestInstanceBuilder.newBuilder(f.app.id).addTaskUnreachable().getInstance()
      val instances = Seq(lostInstance, Instance.Scheduled(f.app))

      instanceTracker.list(any)(any) returns Future.successful(instances)
      val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
      // we're only interested in capturing the argument, so return value doesn't matter
      Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

      val launcherRef = createLauncherRef(constraintApp)
      launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

      val promise = Promise[OfferMatchResult]
      launcherRef ! TaskLauncherActor.MatchOffer(offer, promise, instances)
      promise.future.futureValue

      Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
      captor.getValue.instances should have size (1) // we should have one replacement task scheduled already
      verifyClean()
    }

    "Process task launch reject" in new Fixture {
      val scheduledInstance = Instance.Scheduled(f.app)
      instanceTracker.list(any)(any) returns Future.successful(Seq(scheduledInstance))
      instanceTracker.specInstancesSync(any) returns Seq(scheduledInstance)
      val offer = MarathonTestHelper.makeBasicOffer().build()
      Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      val promise = Promise[OfferMatchResult]
      launcherRef ! TaskLauncherActor.MatchOffer(offer, promise, Seq(scheduledInstance))
      val matchedTasks = promise.future.futureValue

      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      assert(counts.instancesLeftToLaunch == 1)

      assert(counts.inProgress)
      assert(counts.finalInstanceCount == 1)
      assert(counts.instancesLeftToLaunch == 1)
    }

    "Process task launch accept" in new Fixture {
      val scheduledInstance = Instance.Scheduled(f.app)
      instanceTracker.list(any)(any) returns Future.successful(Seq(scheduledInstance))
      val offer = MarathonTestHelper.makeBasicOffer().build()
      instanceOpFactory.matchOfferRequest(m.any()) returns f.launchResult

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      val promise = Promise[OfferMatchResult]
      launcherRef ! TaskLauncherActor.MatchOffer(offer, promise, Seq(scheduledInstance))
      val matchedTasks: OfferMatchResult = promise.future.futureValue

      val runningInstance = f.marathonInstance.copy(instanceId = scheduledInstance.instanceId)
      val update = InstanceUpdated(runningInstance, Some(runningInstance.state), Seq.empty)
      instanceTracker.specInstancesSync(any) returns Seq(runningInstance)
      val counts = sendUpdate(launcherRef, update)

      assert(counts.finalInstanceCount == 1)
      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)
      assert(counts.instancesLeftToLaunch == 0)
    }

    "Expunged task is removed from counts" in new Fixture {
      val update = TaskStatusUpdateTestHelper.finished(f.marathonInstance).wrapped
      val expectedCounts = QueuedInstanceInfo(f.app, inProgress = false, 0, 0, Timestamp(0), Timestamp(0))

      instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))
      instanceTracker.specInstancesSync(any) returns Seq(f.marathonInstance)

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      // task status update
      instanceTracker.specInstancesSync(any) returns Seq(update.instance)
      val counts = sendUpdate(launcherRef, update)

      assert(counts.instancesLeftToLaunch == expectedCounts.instancesLeftToLaunch)
      assert(counts.finalInstanceCount == expectedCounts.finalInstanceCount)
    }

    for (
      update <- TaskConditionMapping.Gone.toSeq.map(reason => TaskStatusUpdateTestHelper.lost(reason, f.marathonInstance))
        .union(Seq(
          TaskStatusUpdateTestHelper.finished(f.marathonInstance),
          TaskStatusUpdateTestHelper.killed(f.marathonInstance),
          TaskStatusUpdateTestHelper.error(f.marathonInstance)))
    ) {
      s"Terminated task (${update.simpleName} with ${update.reason} is removed" in new Fixture {
        instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))
        instanceTracker.specInstancesSync(any) returns Seq(f.marathonInstance)

        val launcherRef = createLauncherRef()
        launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

        // wait for startup
        (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

        // task status update
        val counts = sendUpdate(launcherRef, update.wrapped)

        assert(!counts.inProgress)
        assert(counts.instancesLeftToLaunch == 0)

        verifyClean()
      }
    }

    for (
      reason <- TaskConditionMapping.Unreachable
    ) {
      s"TemporarilyUnreachable task ($reason) is NOT removed" in new Fixture {
        val update = TaskStatusUpdateTestHelper.lost(reason, f.marathonInstance, timestamp = clock.now())
        instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))
        instanceTracker.specInstancesSync(any) returns Seq(f.marathonInstance)

        val launcherRef = createLauncherRef()
        launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

        // wait for startup
        (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

        // task status update
        val counts = sendUpdate(launcherRef, update.wrapped)
        assert(!counts.inProgress)
        assert(counts.finalInstanceCount == 1)
        assert(counts.instancesLeftToLaunch == 0)

        verifyClean()
      }
    }

    "Updated task is reflected in counts" in new Fixture {
      val update = TaskStatusUpdateTestHelper.runningHealthy(f.marathonInstance)
      val expectedCounts = QueuedInstanceInfo(f.app, inProgress = false, 0, 1, Timestamp(0), Timestamp(0))

      instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))
      instanceTracker.specInstancesSync(any) returns Seq(f.marathonInstance)

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      // task status update
      val counts = sendUpdate(launcherRef, update.wrapped)

      assert(counts.instancesLeftToLaunch == expectedCounts.instancesLeftToLaunch)
      assert(counts.finalInstanceCount == expectedCounts.finalInstanceCount)
    }

    for (
      update <- TaskConditionMapping.Gone.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r, f.marathonInstance))
        .union(Seq(
          TaskStatusUpdateTestHelper.finished(f.marathonInstance),
          TaskStatusUpdateTestHelper.killed(f.marathonInstance),
          TaskStatusUpdateTestHelper.error(f.marathonInstance)))
    ) {
      s"Revive offers if task with constraints terminates (${update.simpleName} with ${update.reason})" in new Fixture {
        Given("an actor for an app with constraints and one task")
        val constraint = Protos.Constraint
          .newBuilder()
          .setField("test")
          .setOperator(Protos.Constraint.Operator.CLUSTER)
          .build()
        val appWithConstraints = f.app.copy(constraints = Set(constraint))
        instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))
        instanceTracker.specInstancesSync(any) returns Seq(f.marathonInstance)

        val launcherRef = createLauncherRef(appWithConstraints)
        launcherRef ! RateLimiterActor.DelayUpdate(appWithConstraints, clock.now())

        And("that has successfully started up")
        (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

        When("we get a status update about a terminated task")
        sendUpdate(launcherRef, update.wrapped)

        Then("reviveOffers has been called")
        Mockito.verify(offerReviver).reviveOffers()

        And("the task tracker as well")
        verifyClean()
      }
    }

    for (
      update <- Seq(
        TaskStatusUpdateTestHelper.staging(f.marathonInstance),
        TaskStatusUpdateTestHelper.running(f.marathonInstance)
      )
    ) {
      s"DO NOT REMOVE running task (${update.simpleName})" in new Fixture {
        instanceTracker.list(any)(any) returns Future.successful(Seq(f.marathonInstance))
        instanceTracker.specInstancesSync(any) returns Seq(f.marathonInstance)

        val launcherRef = createLauncherRef()
        launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

        // wait for startup
        (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

        // task status update
        val counts = sendUpdate(launcherRef, update.wrapped)

        assert(counts.finalInstanceCount == 1)
        assert(!counts.inProgress)
        assert(counts.instancesLeftToLaunch == 0)

        verifyClean()
      }
    }
  }
}
