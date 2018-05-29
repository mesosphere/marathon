package mesosphere.marathon

import java.time.Clock

import akka.Done
import mesosphere.{AkkaUnitTest, WaitTestSupport}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.launcher.{InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.matcher.DummyOfferMatcherManager
import mesosphere.marathon.core.matcher.base.util.OfferMatcherSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.MarathonTestHelper
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
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.forInstances(Instance.Scheduled(app))
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)

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
      val scheduledInstance = Instance.Scheduled(app)
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.forInstances(scheduledInstance)
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
      val request = InstanceOpFactory.Request(app, offer, Seq.empty, scheduledInstances = Iterable(scheduledInstance))
      verify(instanceOpFactory).matchOfferRequest(request)
      matchedTasks.offerId should equal(offer.getId)
      matchedTasks.opsWithSource should equal(Seq.empty)
    }

    "an offer gets successfully matched against an item in the queue" in fixture { f =>
      import f._
      Given("An app in the queue")
      val scheduledInstance = Instance.Scheduled(app)
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.forInstances(scheduledInstance)
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      launchQueue.add(app).futureValue
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }

      When("we ask for matching an offer")
      instanceOpFactory.matchOfferRequest(Matchers.any()) returns launchResult
      val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(offer)
      val matchedTasks = matchFuture.futureValue

      Then("the offer gets passed to the task factory and respects the answer")
      val request = InstanceOpFactory.Request(app, offer, Seq.empty, scheduledInstances = Iterable(scheduledInstance))
      verify(instanceOpFactory).matchOfferRequest(request)
      matchedTasks.offerId should equal(offer.getId)
      launchedTaskInfos(matchedTasks) should equal(Seq(mesosTask))
    }
  }

  class Fixture extends AutoCloseable {
    val app = MarathonTestHelper.makeBasicApp().copy(id = PathId("/app"))

    val offer = MarathonTestHelper.makeBasicOffer().build()
    val runspecId = PathId("/test")
    val instance = TestInstanceBuilder.newBuilder(runspecId).addTaskWithBuilder().taskRunning().build().getInstance()
    val task: Task = instance.appTask

    val mesosTask = MarathonTestHelper.makeOneCPUTask(task.taskId).build()
    val launch = new InstanceOpFactoryHelper(Some("principal"), Some("role")).
      launchEphemeral(mesosTask, task, instance)
    val instanceChange = TaskStatusUpdateTestHelper(
      operation = InstanceUpdateOperation.LaunchEphemeral(instance),
      effect = InstanceUpdateEffect.Update(instance = instance, oldState = None, events = Nil)).wrapped

    lazy val clock: Clock = Clock.systemUTC()
    val noMatchResult = OfferMatchResult.NoMatch(app, offer, Seq.empty, clock.now())
    val launchResult = OfferMatchResult.Match(app, offer, launch, clock.now())

    lazy val offerMatcherManager: DummyOfferMatcherManager = new DummyOfferMatcherManager()
    lazy val instanceTracker: InstanceTracker = mock[InstanceTracker]
    lazy val instanceOpFactory: InstanceOpFactory = mock[InstanceOpFactory]
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
      localRegion
    )

    def launchQueue = module.launchQueue

    def close(): Unit = {
      parentActor.stop()
    }
  }
}
