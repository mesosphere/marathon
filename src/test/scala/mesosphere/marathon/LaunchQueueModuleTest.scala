package mesosphere.marathon

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.TaskOpFactory
import mesosphere.marathon.core.launcher.impl.TaskOpFactoryHelper
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.matcher.DummyOfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.integration.setup.WaitTestSupport
import mesosphere.marathon.state.{ AppRepository, PathId }
import mesosphere.marathon.test.{ MarathonShutdownHookSupport, Mockito }
import org.mockito.Matchers
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers => ScalaTestMatchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class LaunchQueueModuleTest
    extends MarathonSpec
    with BeforeAndAfter with GivenWhenThen with MarathonShutdownHookSupport with ScalaTestMatchers
    with Mockito {

  test("empty queue returns no results") {
    val f = new Fixture
    import f._
    When("querying queue")
    val apps = taskQueue.list

    Then("no apps are returned")
    apps should be(empty)

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("An added queue item is returned in list") {
    val f = new Fixture
    import f._
    Given("a task queue with one item")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskQueue.add(app)

    When("querying its contents")
    val list = taskQueue.list

    Then("we get back the added app")
    list should have size 1
    list.head.app should equal(app)
    list.head.tasksLeftToLaunch should equal(1)
    list.head.tasksLaunched should equal(0)
    list.head.taskLaunchesInFlight should equal(0)

    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("An added queue item is reflected via count") {
    val f = new Fixture
    import f._
    Given("a task queue with one item")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskQueue.add(app)

    When("querying its count")
    val count = taskQueue.count(app.id)

    Then("we get a count == 1")
    count should be(1)
    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("A purged queue item has a count of 0") {
    val f = new Fixture
    import f._
    Given("a task queue with one item which is purged")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskQueue.add(app)
    taskQueue.purge(app.id)

    When("querying its count")
    val count = taskQueue.count(app.id)

    Then("we get a count == 0")
    count should be (0)
    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("A re-added queue item has a count of 1") {
    val f = new Fixture
    import f._
    Given("a task queue with one item which is purged")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskQueue.add(app)
    taskQueue.purge(app.id)
    taskQueue.add(app)

    When("querying its count")
    val count = taskQueue.count(app.id)

    Then("we get a count == 1")
    count should be(1)
    verify(taskTracker, times(2)).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("adding a queue item registers new offer matcher") {
    val f = new Fixture
    import f._
    Given("An empty task tracker")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty

    When("Adding an app to the taskQueue")
    taskQueue.add(app)

    Then("A new offer matcher gets registered")
    WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
      offerMatcherManager.offerMatchers.size == 1
    }
    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("purging a queue item UNregisters offer matcher") {
    val f = new Fixture
    import f._
    Given("An app in the queue")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskQueue.add(app)

    When("The app is purged")
    taskQueue.purge(app.id)

    Then("No offer matchers remain registered")
    offerMatcherManager.offerMatchers should be(empty)
    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("an offer gets unsuccessfully matched against an item in the queue") {
    val f = new Fixture
    import f._
    val offer = MarathonTestHelper.makeBasicOffer().build()

    Given("An app in the queue")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskQueue.add(app)
    WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
      offerMatcherManager.offerMatchers.size == 1
    }

    When("we ask for matching an offer")
    taskOpFactory.buildTaskOp(Matchers.any()) returns None
    val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(clock.now() + 3.seconds, offer)
    val matchedTasks = Await.result(matchFuture, 3.seconds)

    Then("the offer gets passed to the task factory and respects the answer")
    val request = TaskOpFactory.Request(app, offer, Iterable.empty, additionalLaunches = 1)
    verify(taskOpFactory).buildTaskOp(request)
    matchedTasks.offerId should equal(offer.getId)
    matchedTasks.opsWithSource should equal(Seq.empty)

    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("an offer gets successfully matched against an item in the queue") {
    val f = new Fixture
    import f._
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val taskId = Task.Id.forApp(PathId("/test"))
    val mesosTask = MarathonTestHelper.makeOneCPUTask("").setTaskId(taskId.mesosTaskId).build()
    val marathonTask = MarathonTestHelper.mininimalTask(taskId)
    val launch = new TaskOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(mesosTask, marathonTask)

    Given("An app in the queue")
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty
    taskOpFactory.buildTaskOp(Matchers.any()) returns Some(launch)
    taskQueue.add(app)
    WaitTestSupport.waitUntil("registered as offer matcher", 1.second) {
      offerMatcherManager.offerMatchers.size == 1
    }

    When("we ask for matching an offer")
    val matchFuture = offerMatcherManager.offerMatchers.head.matchOffer(clock.now() + 3.seconds, offer)
    val matchedTasks = Await.result(matchFuture, 3.seconds)

    Then("the offer gets passed to the task factory and respects the answer")
    val request = TaskOpFactory.Request(app, offer, Iterable.empty, additionalLaunches = 1)
    verify(taskOpFactory).buildTaskOp(request)
    matchedTasks.offerId should equal (offer.getId)
    matchedTasks.launchedTaskInfos should equal (Seq(mesosTask))

    verify(taskTracker).tasksByAppSync

    And("there should be no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    val app = MarathonTestHelper.makeBasicApp().copy(id = PathId("/app"))

    lazy val clock: Clock = Clock()
    lazy val taskBusModule: TaskBusModule = new TaskBusModule()
    lazy val offerMatcherManager: DummyOfferMatcherManager = new DummyOfferMatcherManager()
    lazy val appRepository: AppRepository = mock[AppRepository]
    lazy val taskTracker: TaskTracker = mock[TaskTracker]
    lazy val taskOpFactory: TaskOpFactory = mock[TaskOpFactory]
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val module: LaunchQueueModule = new LaunchQueueModule(
      config,
      AlwaysElectedLeadershipModule(shutdownHooks),
      clock,
      subOfferMatcherManager = offerMatcherManager,
      maybeOfferReviver = None,
      appRepository,
      taskTracker,
      taskOpFactory
    )

    def taskQueue = module.taskQueue

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(appRepository)
      noMoreInteractions(taskTracker)
      noMoreInteractions(taskOpFactory)
    }
  }
}
