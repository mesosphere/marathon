package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.MesosUpdate
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted, InstanceUpdateOperation, InstanceUpdated, InstanceUpdater}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launcher.InstanceOp.LaunchTask
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.launcher.{InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder, TaskConditionMapping}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos
import org.apache.mesos.Protos.TaskStatus
import org.mockito
import org.mockito.{ArgumentCaptor, Mockito}

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
class TaskLauncherActorTest extends AkkaUnitTest {

  import org.mockito.{Matchers => m}

  object f {
    import org.apache.mesos.{Protos => Mesos}
    val app = AppDefinition(id = PathId("/testapp"))
    val scheduledInstance = Instance.Scheduled(app)
    val provisionedInstance = Instance.Provisioned(scheduledInstance, AgentInfoPlaceholder(), NetworkInfoPlaceholder(), app, Timestamp.now())
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
      offerMatchStatisticsActor: TestProbe = TestProbe(),
      localRegion: () => Option[Region] = () => None) {

    Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.scheduledInstance))

    def createLauncherRef(appToLaunch: AppDefinition = f.app): ActorRef = {
      val props = TaskLauncherActor.props(
        launchQueueConfig,
        offerMatcherManager, clock, instanceOpFactory,
        maybeOfferReviver = Some(offerReviver),
        instanceTracker, rateLimiterActor.ref, offerMatchStatisticsActor.ref, localRegion) _
      TestActorRef[TaskLauncherActor](props(appToLaunch))
    }

    def verifyClean(): Unit = {
      // we are not interested in these. We check for these in LaunchQueueModuleTest
      // Mockito.verifyNoMoreInteractions(offerMatcherManager)
      Mockito.verifyNoMoreInteractions(instanceOpFactory)
    }
  }

  "TaskLauncherActor" should {

    "Initial population of task list from instanceTracker with one task" in new Fixture {
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance))

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      assert(counts.finalInstanceCount == 1)
      assert(counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(instanceTracker).instancesBySpecSync
      verifyClean()
    }

    // This test does not apply to the new task launcher. The number of scheduled instances should not be defined in the
    // task launcher but outside.
    "upgrade an app updates app definition in actor and requeries backoff" ignore new Fixture {
      Given("an entry for an app")
      val instances = Seq(
        f.provisionedInstance,
        Instance.Scheduled(f.app, Instance.Id.forRunSpec(f.app.id)),
        Instance.Scheduled(f.app, Instance.Id.forRunSpec(f.app.id)),
        Instance.Scheduled(f.app, Instance.Id.forRunSpec(f.app.id))
      )
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(instances))
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
      launcherRef ! TaskLauncherActor.Sync(upgradedApp) //TaskLauncherActor.AddInstances(upgradedApp, 1)

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
      val instances = Seq(f.provisionedInstance, Instance.Scheduled(f.app))
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(instances))
      val launcherRef = createLauncherRef()
      rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
      rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
      assert(counts.instancesLeftToLaunch == 1)

      // We don't care about interactions until this point
      Mockito.reset(offerMatcherManager)

      When("upgrading the app")
      val upgradedApp = f.app.copy(cmd = Some("new command"))
      launcherRef ! TaskLauncherActor.Sync(upgradedApp) //TaskLauncherActor.AddInstances(upgradedApp, 1)
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
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance, scheduledInstance))
      val offer = MarathonTestHelper.makeBasicOffer().build()
      Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

      val launcherRef = createLauncherRef()
      val now = clock.now()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, now)

      When("the launcher receives an offer")
      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)

      Then("it is matched")
      promise.future.futureValue

      When("the launcher receives the update for the provisioned instance")
      val provisionedInstance = scheduledInstance.copy(state = Instance.InstanceState(Condition.Provisioned, clock.now(), None, None))
      val update = InstanceUpdated(provisionedInstance, Some(scheduledInstance.state), Seq.empty)
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance, provisionedInstance))
      launcherRef ! update
      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

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

      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance, Instance.Scheduled(f.app)))
      val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
      // we're only interested in capturing the argument, so return value doesn't matter
      Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

      val launcherRef = createLauncherRef(constraintApp)
      launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      promise.future.futureValue

      Mockito.verify(instanceTracker).instancesBySpecSync
      Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
      assert(captor.getValue.instanceMap.isEmpty)
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

      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(lostInstance, Instance.Scheduled(f.app)))
      val captor = ArgumentCaptor.forClass(classOf[InstanceOpFactory.Request])
      // we're only interested in capturing the argument, so return value doesn't matter
      Mockito.when(instanceOpFactory.matchOfferRequest(captor.capture())).thenReturn(f.noMatchResult)

      val launcherRef = createLauncherRef(constraintApp)
      launcherRef ! RateLimiterActor.DelayUpdate(constraintApp, clock.now())

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      promise.future.futureValue

      Mockito.verify(instanceTracker).instancesBySpecSync
      Mockito.verify(instanceOpFactory).matchOfferRequest(m.any())
      assert(captor.getValue.instanceMap.size == 1) // we should have one replacement task scheduled already
      verifyClean()
    }

    "Process task launch reject" in new Fixture {
      val offer = MarathonTestHelper.makeBasicOffer().build()
      Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      val matchedTasks = promise.future.futureValue
      matchedTasks.opsWithSource.foreach(_.reject("stuff"))

      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      assert(counts.instancesLeftToLaunch == 1)

      assert(counts.inProgress)
      assert(counts.finalInstanceCount == 1)
      assert(counts.instancesLeftToLaunch == 1)
    }

    "consider finished when update comes and instance tracker contains running instance" in new Fixture {
      val offer = MarathonTestHelper.makeBasicOffer().build()
      Mockito.when(instanceOpFactory.matchOfferRequest(m.any())).thenReturn(f.launchResult)

      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      val promise = Promise[MatchedInstanceOps]
      launcherRef ! ActorOfferMatcher.MatchOffer(offer, promise)
      val matchedTasks: MatchedInstanceOps = promise.future.futureValue
      matchedTasks.opsWithSource.foreach(_.accept())

      val runningInstance = TestInstanceBuilder.newBuilder(f.app.id).addTaskRunning().instance
      val update = InstanceUpdated(runningInstance, Some(runningInstance.state), Seq.empty)
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(runningInstance))
      launcherRef ! update
      val counts = (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]

      assert(counts.finalInstanceCount == 1)
      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)
      assert(counts.instancesLeftToLaunch == 0)
    }

    "Revive offers if instance deleted" in new Fixture {
      val constraint = Protos.Constraint
        .newBuilder()
        .setField("test")
        .setOperator(Protos.Constraint.Operator.CLUSTER)
        .build()
      val appWithConstraints = f.app.copy(constraints = Set(constraint))
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance))
      val launcherRef = createLauncherRef(appWithConstraints)
      launcherRef ! RateLimiterActor.DelayUpdate(appWithConstraints, clock.now())

      // wait for startup
      (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
      launcherRef ! InstanceDeleted(f.provisionedInstance, Some(f.scheduledInstance.state), Seq.empty)

      Mockito.verify(offerReviver).reviveOffers()
    }

    "do instance tracker call after InstanceChange event" in new Fixture {
      Mockito.when(instanceTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forInstances(f.provisionedInstance))
      val launcherRef = createLauncherRef()
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      (launcherRef ? TaskLauncherActor.GetCount).futureValue.asInstanceOf[QueuedInstanceInfo]
      launcherRef ! InstanceUpdated(f.scheduledInstance, Some(f.scheduledInstance.state), Seq.empty)

      Mockito.verify(instanceTracker, times(2)).instancesBySpecSync
    }
  }
}
