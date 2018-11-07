package mesosphere.marathon
package core.launchqueue.impl

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.{InstanceUpdateOperation, InstanceUpdated}
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launcher.InstanceOp.LaunchTask
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.core.matcher.base.util.{ActorOfferMatcher, InstanceOpSourceDelegate}
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder, TaskConditionMapping}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state._
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import org.mockito
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.concurrent.Eventually

import scala.collection.immutable.Seq
import scala.concurrent.Promise
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
class TaskLauncherActorTest extends AkkaUnitTest with Eventually {

  import org.mockito.{Matchers => m}

  private[impl] def activeCount(ref: TestActorRef[TaskLauncherActor]) =
    ref.underlyingActor.instanceMap.values.count(_.isActive)

  private[impl] def inProgress(ref: TestActorRef[TaskLauncherActor]): Boolean =
    ref.underlyingActor.scheduledInstances.nonEmpty ||
      ref.underlyingActor.inFlightInstanceOperations.nonEmpty

  object f {
    import org.apache.mesos.{Protos => Mesos}
    val app = AppDefinition(id = PathId("/testapp"))
    val scheduledInstance = Instance.scheduled(app)
    val taskId = Task.Id.forInstanceId(scheduledInstance.instanceId)
    val provisionedInstance = scheduledInstance.provisioned(AgentInfoPlaceholder(), NetworkInfoPlaceholder(), app, Timestamp.now(), taskId)
    val runningInstance = TestInstanceBuilder.newBuilder(app.id, version = app.version, now = Timestamp.now()).addTaskRunning().getInstance()
    val marathonTask: Task = provisionedInstance.appTask
    val provisionedInstanceId = provisionedInstance.instanceId
    val taskInfo = MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(provisionedInstanceId, None)).build()
    val launch = LaunchTask(taskInfo, InstanceUpdateOperation.Provision(scheduledInstance), Some(scheduledInstance), Seq.empty)
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
      localRegion: () => Option[Region] = () => None) {

    val (offerMatchInput, offerMatchUpdates) =
      Source.queue[OfferMatchStatistics.OfferMatchUpdate](16, OverflowStrategy.fail)
        .toMat(Sink.queue[OfferMatchStatistics.OfferMatchUpdate])(Keep.both)
        .run

    private[impl] def createLauncherRef(appToLaunch: AppDefinition = f.app): TestActorRef[TaskLauncherActor] = {
      val props = TaskLauncherActor.props(
        launchQueueConfig,
        offerMatcherManager, clock, instanceOpFactory,
        maybeOfferReviver = Some(offerReviver),
        instanceTracker, rateLimiterActor.ref, offerMatchInput, localRegion) _
      TestActorRef[TaskLauncherActor](props(appToLaunch))
    }

    def verifyClean(): Unit = {
      // we are not interested in these. We check for these in LaunchQueueModuleTest
      // Mockito.verifyNoMoreInteractions(offerMatcherManager)
      Mockito.verifyNoMoreInteractions(instanceOpFactory)
    }
  }

  "TaskLauncherActor" should {
    "show correct count statistics for one running instance in the state" in new Fixture {
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.runningInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now()))

      launcherRef.underlyingActor.instancesToLaunch shouldBe 0
      activeCount(launcherRef) shouldBe 1
      inProgress(launcherRef) shouldBe (false)

      Mockito.verify(instanceTracker).instancesBySpecSync
      verifyClean()
    }

    // This test does not apply to the new task launcher. The number of scheduled instances should not be defined in the
    // task launcher but outside.
    "upgrade an app updates app definition in actor and requires backoff" in new Fixture {
      Given("an entry for an app")
      val instances = Seq(
        f.provisionedInstance,
        Instance.scheduled(f.app, Instance.Id.forRunSpec(f.app.id)),
        Instance.scheduled(f.app, Instance.Id.forRunSpec(f.app.id)),
        Instance.scheduled(f.app, Instance.Id.forRunSpec(f.app.id))
      )
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(instances))
      val launcherRef = createLauncherRef()
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
      rateLimiterActor.reply(RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now())))

      launcherRef.underlyingActor.instancesToLaunch shouldBe 3
      Mockito.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
      Mockito.reset(offerMatcherManager)

      When("upgrading the app")
      val upgradedApp = f.app.copy(cmd = Some("new command"))
      launcherRef ! TaskLauncherActor.Sync(upgradedApp)

      Then("the actor requeries the backoff delay")
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
      val newDelay: Timestamp = clock.now() + 5.seconds
      rateLimiterActor.reply(RateLimiter.DelayUpdate(upgradedApp.configRef, Some(newDelay)))

      launcherRef.underlyingActor.backOffUntil

      And("the actor knows the new app definition")
      assert(launcherRef.underlyingActor.runSpec == upgradedApp)
    }

    "re-register the offerMatcher when upgrading an app" in new Fixture {
      Given("an entry for an app")
      val instances = Seq(f.provisionedInstance, Instance.scheduled(f.app))
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(instances))
      val launcherRef = createLauncherRef()
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
      rateLimiterActor.reply(RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now())))

      // We don't care about interactions until this point
      Mockito.reset(offerMatcherManager)

      When("upgrading the app")
      val upgradedApp = f.app.copy(cmd = Some("new command"))
      launcherRef ! TaskLauncherActor.Sync(upgradedApp) //TaskLauncherActor.AddInstances(upgradedApp, 1)
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
      rateLimiterActor.reply(RateLimiter.DelayUpdate(upgradedApp.configRef, Some(clock.now())))

      Then("the actor re-registers itself for at the offerMatcher")
      val inOrder = Mockito.inOrder(offerMatcherManager)
      inOrder.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())
      inOrder.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    }

    "match offer when in progress and offer received" in new Fixture {
      Given("a scheduled and a running instance")
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.runningInstance, f.scheduledInstance))
      val offer = MarathonTestHelper.makeBasicOffer().build()
      Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

      val launcherRef = createLauncherRef()
      val now = clock.now()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(now))

      When("the launcher receives an offer")
      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)

      Then("it is matched")
      promise.future.futureValue
    }

    // task launcher actor for every update just polls the state from instance tracker
    // this test verifies that this poll happened
    "update the internal when instance update is received" in new Fixture {
      Given("a scheduled and a running instance")
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.runningInstance, f.scheduledInstance))
      val launcherRef = createLauncherRef()
      val now = clock.now()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(now))

      When("the launcher receives the update for the provisioned instance")
      val taskId = Task.Id.forInstanceId(f.scheduledInstance.instanceId)
      val provisionedInstance = f.scheduledInstance.provisioned(TestInstanceBuilder.defaultAgentInfo, NetworkInfoPlaceholder(), f.app, clock.now(), taskId)
      val update = InstanceUpdated(provisionedInstance, Some(f.scheduledInstance.state), Seq.empty)
      // setting new state in instancetracker here
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.runningInstance, provisionedInstance))
      launcherRef ! update

      Then("there are not instances left to launch")
      activeCount(launcherRef) shouldBe 2
      inProgress(launcherRef) shouldBe true
      launcherRef.underlyingActor.instancesToLaunch shouldBe 0
    }

    "not use unreachable instance when matching an offer if unreachable but not inactive" in new Fixture {
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

      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance, Instance.scheduled(f.app)))
      val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
      // we're only interested in capturing the argument, so return value doesn't matter
      Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

      val launcherRef = createLauncherRef(constraintApp)
      launcherRef ! RateLimiter.DelayUpdate(constraintApp.configRef, Some(clock.now()))

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      promise.future.futureValue

      Mockito.verify(instanceTracker).instancesBySpecSync
      Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
      assert(captor.getValue.instanceMap.isEmpty)
      verifyClean()
    }

    "use unreachable inactive task when matching an offer" in new Fixture {
      import mesosphere.marathon.Protos.Constraint.Operator

      val uniqueConstraint = Protos.Constraint.newBuilder
        .setField("hostname")
        .setOperator(Operator.UNIQUE)
        .setValue("")
        .build
      val constraintApp: AppDefinition = f.app.copy(constraints = Set(uniqueConstraint))
      val offer = MarathonTestHelper.makeBasicOffer().build()

      val lostInstance = TestInstanceBuilder.newBuilder(f.app.id).addTaskUnreachable().getInstance()

      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance, Instance.scheduled(f.app)))
      val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
      // we're only interested in capturing the argument, so return value doesn't matter
      Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

      val launcherRef = createLauncherRef(constraintApp)
      launcherRef ! RateLimiter.DelayUpdate(constraintApp.configRef, Some(clock.now()))

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      promise.future.futureValue

      Mockito.verify(instanceTracker).instancesBySpecSync
      Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
      // The unreachable inactive is not considered lost.
      assert(captor.getValue.instanceMap.size == 1)
      verifyClean()
    }

    "process task launch reject" in new Fixture {
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.scheduledInstance))
      val offer = MarathonTestHelper.makeBasicOffer().build()
      Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now()))

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      val matchedTasks = promise.future.futureValue
      matchedTasks.opsWithSource.foreach(_.reject("stuff"))

      assert(launcherRef.underlyingActor.instancesToLaunch == 1)

      assert(inProgress(launcherRef))
      assert(activeCount(launcherRef) == 0)
      assert(launcherRef.underlyingActor.instancesToLaunch == 1)
    }

    "decomissioned task is not counted in as active or to be launched" in new Fixture {
      val update = TaskStatusUpdateTestHelper.finished(f.provisionedInstance).wrapped
      val updatedInstance = update.instance.copy(state = update.instance.state.copy(goal = Goal.Decommissioned))

      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now()))

      // task status update
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(updatedInstance))
      launcherRef ! update

      assert(launcherRef.underlyingActor.instancesToLaunch == 0)
      assert(activeCount(launcherRef) == 0)
    }

    s"unreachable task queue statistics return expected values" in new Fixture {
      val lostInstance = TestInstanceBuilder.newBuilder(f.app.id, version = f.app.version, now = Timestamp.now()).addTaskWithBuilder().taskUnreachable().build().instance
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now()))

      launcherRef.underlyingActor.instancesToLaunch shouldBe 0
      activeCount(launcherRef) shouldBe 1
      inProgress(launcherRef) shouldBe (false)

      verifyClean()
    }

    for (
      update <- TaskConditionMapping.Gone.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r, f.provisionedInstance))
        .union(Seq(
          TaskStatusUpdateTestHelper.finished(f.provisionedInstance),
          TaskStatusUpdateTestHelper.killed(f.provisionedInstance),
          TaskStatusUpdateTestHelper.error(f.provisionedInstance)))
    ) {
      s"revive offers if task with constraints terminates (${update.simpleName} with ${update.reason})" in new Fixture {
        Given("an actor for an app with constraints and one task")
        val constraint = Protos.Constraint
          .newBuilder()
          .setField("test")
          .setOperator(Protos.Constraint.Operator.CLUSTER)
          .build()
        val appWithConstraints = f.app.copy(constraints = Set(constraint))
        Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance))

        val launcherRef = createLauncherRef(appWithConstraints)
        launcherRef ! RateLimiter.DelayUpdate(appWithConstraints.configRef, Some(clock.now()))

        When("we get a status update about a terminated task")
        launcherRef ! update.wrapped

        Then("reviveOffers has been called")
        Mockito.verify(offerReviver).reviveOffers()

        And("the task tracker as well")
        verifyClean()
      }
    }

    "reschedule instance on provision timeout" in new Fixture {
      Given("a provisioned instance")

      val scheduledInstanceB = Instance.scheduled(f.app)
      val taskId = Task.Id.forInstanceId(scheduledInstanceB.instanceId)
      val provisionedInstance = scheduledInstanceB.provisioned(TestInstanceBuilder.defaultAgentInfo, NetworkInfoPlaceholder(), f.app, clock.now(), taskId)
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.scheduledInstance, provisionedInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now()))

      When("the provision times out")
      val op = mock[InstanceOp]
      op.instanceId returns provisionedInstance.instanceId
      launcherRef ! InstanceOpSourceDelegate.InstanceOpRejected(op, TaskLauncherActor.OfferOperationRejectedTimeoutReason)

      Then("the instance is rescheduled")
      eventually {
        verify(instanceTracker).forceExpunge(provisionedInstance.instanceId)
      }
    }

    "not reschedule instance on provision time out for a running instance" in new Fixture {
      Given("a running instance")
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.runningInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiter.DelayUpdate(f.app.configRef, Some(clock.now()))

      When("the provision times out")
      val op = mock[InstanceOp]
      op.instanceId returns f.provisionedInstance.instanceId
      launcherRef ! InstanceOpSourceDelegate.InstanceOpRejected(op, TaskLauncherActor.OfferOperationRejectedTimeoutReason)

      Then("the instance is not rescheduled")
      verify(instanceTracker, never).forceExpunge(any[Instance.Id])
    }
  }
}
