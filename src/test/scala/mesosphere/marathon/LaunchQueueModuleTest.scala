package mesosphere.marathon

import java.time.Clock

import akka.Done
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.launcher.InstanceOp.LaunchTask
import mesosphere.marathon.core.launcher.{InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.matcher.DummyOfferMatcherManager
import mesosphere.marathon.core.matcher.base.util.OfferMatcherSpec
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.{AkkaUnitTest, WaitTestSupport}
import org.mockito.Matchers

import scala.concurrent.Future
import scala.concurrent.duration._

class LaunchQueueModuleTest extends AkkaUnitTest with OfferMatcherSpec {

  def fixture(fn: Fixture => Unit): Unit = {
    val f = new Fixture
    try fn(f)
    finally f.close()
  }

  "LaunchQueueModule" should {

    "adding a queue item registers new offer matcher" in fixture { f =>
      import f._
      Given("An empty task tracker")
      instanceTracker.specInstances(any[PathId])(any) returns Future.successful(Seq.empty)
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.forInstances(Instance.scheduled(app))
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      instanceTracker.process(any[InstanceUpdateOperation]) returns Future.successful[InstanceUpdateEffect](InstanceUpdateEffect.Noop(null))

      When("Adding an app to the launchQueue")
      launchQueue.add(app).futureValue

      Then("A new offer matcher gets registered")
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }
    }

    "purging a queue item UNregisters offer matcher" in fixture { f =>
      import f._
      Given("An app in the queue")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      instanceTracker.specInstances(app.id) returns Future.successful(Seq.empty)
      instanceTracker.forceExpunge(any) returns Future.successful(Done)
      launchQueue.add(app).futureValue

      When("The app is purged")
      launchQueue.purge(app.id).futureValue

      Then("No offer matchers remain registered")
      offerMatcherManager.offerMatchers should be(empty)
    }

    "an offer gets unsuccessfully matched against an item in the queue" in fixture { f =>
      import f._

      Given("An app in the queue")
      val scheduledInstance = Instance.scheduled(app)
      instanceTracker.specInstances(any[PathId])(any) returns Future.successful(Seq.empty)
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.forInstances(scheduledInstance)
      instanceTracker.process(any[InstanceUpdateOperation]) returns Future.successful[InstanceUpdateEffect](InstanceUpdateEffect.Noop(null))
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      launchQueue.add(app).futureValue
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }

      When("we ask for matching an offer")
      instanceOpFactory.matchOfferRequest(Matchers.any()) returns noMatchResult
      val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(offer)
      val matchedTasks = matchFuture.futureValue

      Then("the offer gets passed to the task factory and respects the answer")
      val request = InstanceOpFactory.Request(offer, Map.empty, scheduledInstances = NonEmptyIterable(scheduledInstance))
      verify(instanceOpFactory).matchOfferRequest(request)
      matchedTasks.offerId should equal(offer.getId)
      matchedTasks.opsWithSource should equal(Seq.empty)
    }

    "an offer gets successfully matched against an item in the queue" in fixture { f =>
      import f._
      Given("An app in the queue")
      instanceTracker.specInstances(any[PathId])(any) returns Future.successful(Seq.empty)
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.forInstances(scheduledInstance)
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      instanceTracker.process(any[InstanceUpdateOperation]) returns Future.successful[InstanceUpdateEffect](InstanceUpdateEffect.Noop(null))
      launchQueue.add(app).futureValue
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }

      When("we ask for matching an offer")
      instanceOpFactory.matchOfferRequest(Matchers.any()) returns launchResult
      val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(offer)
      val matchedTasks = matchFuture.futureValue

      Then("the offer gets passed to the task factory and respects the answer")
      val request = InstanceOpFactory.Request(offer, Map.empty, scheduledInstances = NonEmptyIterable(scheduledInstance))
      verify(instanceOpFactory).matchOfferRequest(request)
      matchedTasks.offerId should equal(offer.getId)
      launchedTaskInfos(matchedTasks) should equal(Seq(mesosTask))
    }
  }

  class Fixture extends AutoCloseable {
    val app = MarathonTestHelper.makeBasicApp().copy(id = PathId("/app"))
    val scheduledInstance = Instance.scheduled(app)
    val tasks = Tasks.provisioned(Task.Id(scheduledInstance.instanceId), NetworkInfoPlaceholder(), app.version, Timestamp.now())
    val (_, task) = tasks.head
    val mesosTask = MarathonTestHelper.makeOneCPUTask(task.taskId).build()

    val offer = MarathonTestHelper.makeBasicOffer().build()

    val provisionOp = InstanceUpdateOperation.Provision(scheduledInstance.instanceId, AgentInfoPlaceholder(), app, tasks, Timestamp.now())
    val launchTaskInstanceOp = LaunchTask(mesosTask, provisionOp, Some(scheduledInstance), Seq.empty)
    val instanceChange = TaskStatusUpdateTestHelper(
      operation = provisionOp,
      effect = InstanceUpdateEffect.Update(
        instance = scheduledInstance.provisioned(
          AgentInfoPlaceholder(),
          app,
          Tasks.provisioned(Task.Id(scheduledInstance.instanceId), NetworkInfoPlaceholder(), app.version, Timestamp.now()),
          Timestamp.now()
        ),
        oldState = None,
        events = Nil)).wrapped

    lazy val clock: Clock = Clock.systemUTC()
    val noMatchResult = OfferMatchResult.NoMatch(app, offer, Seq.empty, clock.now())
    val launchResult = OfferMatchResult.Match(app, offer, launchTaskInstanceOp, clock.now())

    lazy val offerMatcherManager: DummyOfferMatcherManager = new DummyOfferMatcherManager()
    lazy val instanceTracker: InstanceTracker = mock[InstanceTracker]
    instanceTracker.instancesBySpec().returns(Future.successful(InstanceTracker.InstancesBySpec.empty))
    instanceTracker.instanceUpdates.returns(Source.empty)
    lazy val instanceOpFactory: InstanceOpFactory = mock[InstanceOpFactory]
    lazy val groupManager: GroupManager = mock[GroupManager]
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val parentActor = newTestActor()
    lazy val localRegion = () => None

    lazy val module: LaunchQueueModule = new LaunchQueueModule(
      config,
      AlwaysElectedLeadershipModule.forRefFactory(parentActor.underlying),
      clock,
      subOfferMatcherManager = offerMatcherManager,
      maybeOfferReviver = None,
      instanceTracker,
      instanceOpFactory,
      groupManager,
      localRegion
    )

    def launchQueue = module.launchQueue

    def close(): Unit = {
      parentActor.stop()
    }
  }
}
