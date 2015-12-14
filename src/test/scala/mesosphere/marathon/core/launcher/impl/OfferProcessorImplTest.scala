package mesosphere.marathon.core.launcher.impl

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launcher.{ OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTasks, TaskLaunchSource, TaskWithSource }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskCreator, TaskIdUtil, TaskTrackerImpl }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos.Protos.TaskInfo
import org.scalatest.GivenWhenThen

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class OfferProcessorImplTest extends MarathonSpec with GivenWhenThen with Mockito {
  private[this] val offer = MarathonTestHelper.makeBasicOffer().build()
  private[this] val offerId = offer.getId
  private val appId: PathId = PathId("/testapp")
  private[this] val taskInfo1 = MarathonTestHelper.makeOneCPUTask(TaskIdUtil.newTaskId(appId).getValue).build()
  private[this] val taskInfo2 = MarathonTestHelper.makeOneCPUTask(TaskIdUtil.newTaskId(appId).getValue).build()
  private[this] val tasks = Seq(taskInfo1, taskInfo2)

  test("match successful, launch tasks successful") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskWithSource(
      dummySource, task, MarathonTestHelper.makeTaskFromTaskInfo(task)))
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second

    And("a cooperative offerMatcher and taskTracker")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTasks(offerId, tasksWithSource))
    for (task <- tasksWithSource) {
      taskCreator.created(appId, task.marathonTask) returns Future.successful(dummyTask(appId))
    }

    And("a working taskLauncher")
    taskLauncher.launchTasks(offerId, tasks) returns true

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the offerMatch request and the task launches")
    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).launchTasks(offerId, tasks)

    And("all task launches have been accepted")
    assert(dummySource.rejected.isEmpty)
    assert(dummySource.accepted.toSeq == tasks)

    And("the tasks have been stored")
    for (task <- tasksWithSource) {
      val ordered = inOrder(taskCreator)
      ordered.verify(taskCreator).created(appId, task.marathonTask)
    }
  }

  test("match successful, launch tasks unsuccessful") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskWithSource(
      dummySource, task, MarathonTestHelper.makeTaskFromTaskInfo(task)))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative offerMatcher and taskTracker")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTasks(offerId, tasksWithSource))
    for (task <- tasksWithSource) {
      taskCreator.created(appId, task.marathonTask) returns Future.successful(dummyTask(appId))
      taskCreator.terminated(appId, task.marathonTask.getId).asInstanceOf[Future[Unit]] returns
        Future.successful(())
    }

    And("a dysfunctional taskLauncher")
    taskLauncher.launchTasks(offerId, tasks) returns false

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request and the task launch attempt")
    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).launchTasks(offerId, tasks)

    And("all task launches were rejected")
    assert(dummySource.accepted.isEmpty)
    assert(dummySource.rejected.toSeq.map(_._1) == tasks)

    And("the tasks where first stored and then expunged again")
    for (task <- tasksWithSource) {
      val ordered = inOrder(taskCreator)
      ordered.verify(taskCreator).created(appId, task.marathonTask)
      ordered.verify(taskCreator).terminated(appId, task.marathonTask.getId)
    }
  }

  test("match successful but very slow so that we are hitting storage timeout") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskWithSource(
      dummySource, task, MarathonTestHelper.makeTaskFromTaskInfo(task)))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative offerMatcher that takes really long")
    offerMatcher.matchOffer(deadline, offer) answers { _ =>
      // advance clock "after" match
      clock += 1.hour
      Future.successful(MatchedTasks(offerId, tasksWithSource))
    }

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request")
    verify(offerMatcher).matchOffer(deadline, offer)

    And("all task launches were rejected")
    assert(dummySource.accepted.isEmpty)
    assert(dummySource.rejected.toSeq.map(_._1) == tasks)

    And("the processor didn't try to launch the tasks")
    verify(taskLauncher, never).launchTasks(offerId, tasks)

    And("no tasks where launched")
    verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = None)
    noMoreInteractions(taskLauncher)

    And("no tasks where stored")
    noMoreInteractions(taskCreator)
  }

  test("match successful but first store is so slow that we are hitting storage timeout") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskWithSource(
      dummySource, task, MarathonTestHelper.makeTaskFromTaskInfo(task)))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative taskLauncher")
    taskLauncher.launchTasks(offerId, tasks.take(1)) returns true

    And("a cooperative offerMatcher")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTasks(offerId, tasksWithSource))

    for (task <- tasksWithSource) {
      taskCreator.created(appId, task.marathonTask) answers { args =>
        // simulate that stores are really slow
        clock += 1.hour
        Future.successful(dummyTask(appId))
      }
      taskCreator.terminated(appId, task.marathonTask.getId).asInstanceOf[Future[Unit]] returns
        Future.successful(Some(task.marathonTask))
    }

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request and the task launch attempt for the first task")
    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).launchTasks(offerId, tasks.take(1))

    And("one task launch was accepted")
    assert(dummySource.accepted.toSeq == tasks.take(1))

    And("one task launch was rejected")
    assert(dummySource.rejected.toSeq.map(_._1) == tasks.drop(1))

    And("the first task was stored")
    for (task <- tasksWithSource.take(1)) {
      val ordered = inOrder(taskCreator)
      ordered.verify(taskCreator).created(appId, task.marathonTask)
    }

    And("and the second task was not stored")
    noMoreInteractions(taskCreator)
  }

  test("match empty => decline") {
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTasks(offerId, Seq.empty))

    Await.result(offerProcessor.processOffer(offer), 1.second)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = Some(conf.declineOfferDuration()))
  }

  test("match crashed => decline") {
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    offerMatcher.matchOffer(deadline, offer) returns Future.failed(new RuntimeException("failed matching"))

    Await.result(offerProcessor.processOffer(offer), 1.second)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = None)
  }

  private[this] var clock: ConstantClock = _
  private[this] var offerMatcher: OfferMatcher = _
  private[this] var taskLauncher: TaskLauncher = _
  private[this] var taskCreator: TaskCreator = _
  private[this] var conf: OfferProcessorConfig = _

  private[this] def createProcessor(): OfferProcessor = {
    conf = new OfferProcessorConfig {}
    conf.afterInit()

    clock = ConstantClock()
    offerMatcher = mock[OfferMatcher]
    taskLauncher = mock[TaskLauncher]
    taskCreator = mock[TaskCreator]

    new OfferProcessorImpl(conf, clock, new Metrics(new MetricRegistry), offerMatcher, taskLauncher, taskCreator)
  }

  class DummySource extends TaskLaunchSource {
    var rejected = Vector.empty[(TaskInfo, String)]
    var accepted = Vector.empty[TaskInfo]

    override def taskLaunchRejected(taskInfo: TaskInfo, reason: String): Unit = rejected :+= taskInfo -> reason
    override def taskLaunchAccepted(taskInfo: TaskInfo): Unit = accepted :+= taskInfo
  }
}
