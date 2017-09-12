package mesosphere.marathon

import java.time.Clock

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.launcher.{ InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.matcher.DummyOfferMatcherManager
import mesosphere.marathon.core.matcher.base.util.OfferMatcherSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.integration.setup.WaitTestSupport
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.MarathonTestHelper
import org.mockito.Matchers

import scala.concurrent.duration._

class LaunchQueueModuleTest extends AkkaUnitTest with OfferMatcherSpec {

  def fixture(fn: Fixture => Unit): Unit = {
    val f = new Fixture
    try fn(f)
    finally f.close()
  }

  "LaunchQueueModule" should {
    "empty queue returns no results" in fixture { f =>
      import f._
      When("querying queue")
      val apps = launchQueue.list

      Then("no apps are returned")
      apps should be(empty)

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "An added queue item is returned in list" in fixture { f =>
      import f._
      Given("a launch queue with one item")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)

      When("querying its contents")
      val list = launchQueue.list

      Then("we get back the added app")
      list should have size 1
      list.head.runSpec should equal(app)
      list.head.instancesLeftToLaunch should equal(1)
      list.head.finalInstanceCount should equal(1)
      list.head.inProgress should equal(true)

      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "An added queue item is reflected via count" in fixture { f =>
      import f._
      Given("a launch queue with one item")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)

      When("querying its count")
      val count = launchQueue.count(app.id)

      Then("we get a count == 1")
      count should be(1)
      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "A purged queue item has a count of 0" in fixture { f =>
      import f._
      Given("a launch queue with one item which is purged")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)
      launchQueue.asyncPurge(app.id).futureValue

      When("querying its count")
      val count = launchQueue.count(app.id)

      Then("we get a count == 0")
      count should be(0)
      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "A re-added queue item has a count of 1" in fixture { f =>
      import f._
      Given("a launch queue with one item which is purged")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)
      launchQueue.asyncPurge(app.id).futureValue
      launchQueue.add(app)

      When("querying its count")
      val count = launchQueue.count(app.id)

      Then("we get a count == 1")
      count should be(1)
      verify(instanceTracker, times(2)).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "adding a queue item registers new offer matcher" in fixture { f =>
      import f._
      Given("An empty task tracker")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty

      When("Adding an app to the launchQueue")
      launchQueue.add(app)

      Then("A new offer matcher gets registered")
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }
      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "purging a queue item UNregisters offer matcher" in fixture { f =>
      import f._
      Given("An app in the queue")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)

      When("The app is purged")
      launchQueue.asyncPurge(app.id).futureValue

      Then("No offer matchers remain registered")
      offerMatcherManager.offerMatchers should be(empty)
      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "an offer gets unsuccessfully matched against an item in the queue" in fixture { f =>
      import f._

      Given("An app in the queue")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }

      When("we ask for matching an offer")
      instanceOpFactory.matchOfferRequest(Matchers.any()) returns noMatchResult
      val now = clock.now()
      val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(offer)
      val matchedTasks = matchFuture.futureValue

      Then("the offer gets passed to the task factory and respects the answer")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      verify(instanceOpFactory).matchOfferRequest(request)
      matchedTasks.offerId should equal(offer.getId)
      matchedTasks.opsWithSource should equal(Seq.empty)

      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "an offer gets successfully matched against an item in the queue" in fixture { f =>
      import f._
      Given("An app in the queue")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app)
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }

      When("we ask for matching an offer")
      instanceOpFactory.matchOfferRequest(Matchers.any()) returns launchResult
      val now = clock.now()
      val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(offer)
      val matchedTasks = matchFuture.futureValue

      Then("the offer gets passed to the task factory and respects the answer")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      verify(instanceOpFactory).matchOfferRequest(request)
      matchedTasks.offerId should equal(offer.getId)
      launchedTaskInfos(matchedTasks) should equal(Seq(mesosTask))

      verify(instanceTracker).instancesBySpecSync

      And("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }

    "TaskChanged updates are answered immediately for suspended queue entries" in fixture { f =>
      // otherwise we get a deadlock in some cases, see comment in LaunchQueueActor
      import f._
      Given("An app in the queue")
      instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.empty
      launchQueue.add(app, 3)
      WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
        offerMatcherManager.offerMatchers.size == 1
      }

      And("a task gets launched but not confirmed")
      instanceOpFactory.matchOfferRequest(Matchers.any()) returns launchResult
      val now = clock.now()
      val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(offer)
      matchFuture.futureValue

      And("test app gets purged (but not stopped yet because of in-flight tasks)")
      launchQueue.asyncPurge(app.id)
      WaitTestSupport.waitUntil("purge gets executed", 1.second) {
        !launchQueue.list.exists(_.runSpec.id == app.id)
      }
      reset(instanceTracker, instanceOpFactory)

      When("we send a related task change")
      val notificationAck = launchQueue.notifyOfInstanceUpdate(instanceChange)

      Then("there should be no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  class Fixture extends AutoCloseable {
    val app = MarathonTestHelper.makeBasicApp().copy(id = PathId("/app"))

    val offer = MarathonTestHelper.makeBasicOffer().build()
    val runspecId = PathId("/test")
    val instance = TestInstanceBuilder.newBuilder(runspecId).addTaskWithBuilder().taskRunning().build().getInstance()
    val task: Task.LaunchedEphemeral = instance.appTask

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

    lazy val module: LaunchQueueModule = new LaunchQueueModule(
      config,
      AlwaysElectedLeadershipModule.forRefFactory(parentActor.underlying),
      clock,
      subOfferMatcherManager = offerMatcherManager,
      maybeOfferReviver = None,
      instanceTracker,
      instanceOpFactory
    )

    def launchQueue = module.launchQueue

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(instanceTracker)
      noMoreInteractions(instanceOpFactory)
    }

    def close(): Unit = {
      parentActor.stop()
    }
  }
}
