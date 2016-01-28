package mesosphere.marathon.core.launcher.impl

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launcher.{ OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOp, TaskOpSource, TaskOpWithSource }
import mesosphere.marathon.core.task.tracker.TaskCreationHandler
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.TaskIdUtil
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
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
    val tasksWithSource = tasks.map(task => TaskOpWithSource(
      dummySource, OfferMatcher.Launch(task, MarathonTestHelper.makeTaskFromTaskInfo(task))))
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second

    And("a cooperative offerMatcher and taskTracker")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTaskOps(offerId, tasksWithSource))
    for (task <- tasks) {
      taskCreationHandler.created(appId, MarathonTestHelper.makeTaskFromTaskInfo(task)) returns
        Future.successful(dummyTask(appId))
    }

    And("a working taskLauncher")
    val ops: Seq[TaskOp] = tasksWithSource.map(_.op)
    taskLauncher.acceptOffer(offerId, ops) returns true

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the offerMatch request and the task launches")
    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).acceptOffer(offerId, ops)

    And("all task launches have been accepted")
    assert(dummySource.rejected.isEmpty)
    assert(dummySource.accepted.toSeq == tasksWithSource.map(_.op))

    And("the tasks have been stored")
    for (task <- tasksWithSource) {
      val ordered = inOrder(taskCreationHandler)
      ordered.verify(taskCreationHandler).created(appId, task.op.newTask)
    }
  }

  test("match successful, launch tasks unsuccessful") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskOpWithSource(
      dummySource, OfferMatcher.Launch(task, MarathonTestHelper.makeTaskFromTaskInfo(task))))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative offerMatcher and taskTracker")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTaskOps(offerId, tasksWithSource))
    for (task <- tasksWithSource) {
      val op = task.op
      taskCreationHandler.created(appId, op.newTask) returns Future.successful(dummyTask(appId))
      taskCreationHandler.terminated(appId, op.newTask.getId).asInstanceOf[Future[Unit]] returns
        Future.successful(())
    }

    And("a dysfunctional taskLauncher")
    taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op)) returns false

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request and the task launch attempt")
    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).acceptOffer(offerId, tasksWithSource.map(_.op))

    And("all task launches were rejected")
    assert(dummySource.accepted.isEmpty)
    assert(dummySource.rejected.toSeq.map(_._1) == tasksWithSource.map(_.op))

    And("the tasks where first stored and then expunged again")
    for (task <- tasksWithSource) {
      val ordered = inOrder(taskCreationHandler)
      val op = task.op
      ordered.verify(taskCreationHandler).created(appId, op.newTask)
      ordered.verify(taskCreationHandler).terminated(appId, op.newTask.getId)
    }
  }

  test("match successful, launch tasks unsuccessful, revert to prior task state") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map { task =>
      val dummyTask = MarathonTask.newBuilder().setId(task.getTaskId.getValue).build()
      val launch = OfferMatcher.Launch(
        task,
        newTask = MarathonTestHelper.makeTaskFromTaskInfo(task),
        oldTask = Some(dummyTask)
      )
      TaskOpWithSource(dummySource, launch)
    }

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative offerMatcher and taskTracker")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTaskOps(offerId, tasksWithSource))
    for (task <- tasksWithSource) {
      val op = task.op
      taskCreationHandler.created(appId, op.newTask) returns Future.successful(op.newTask)
      taskCreationHandler.created(appId, op.oldTask.get) returns Future.successful(op.oldTask.get)
    }

    And("a dysfunctional taskLauncher")
    taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op)) returns false

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request and the task launch attempt")
    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).acceptOffer(offerId, tasksWithSource.map(_.op))

    And("all task launches were rejected")
    assert(dummySource.accepted.isEmpty)
    assert(dummySource.rejected.toSeq.map(_._1) == tasksWithSource.map(_.op))

    And("the tasks where first stored and then expunged again")
    for (task <- tasksWithSource) {
      val op = task.op
      val ordered = inOrder(taskCreationHandler)
      ordered.verify(taskCreationHandler).created(appId, op.newTask)
      ordered.verify(taskCreationHandler).created(appId, op.oldTask.get)
    }
  }

  test("match successful but very slow so that we are hitting storage timeout") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskOpWithSource(
      dummySource, OfferMatcher.Launch(task, MarathonTestHelper.makeTaskFromTaskInfo(task))))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative offerMatcher that takes really long")
    offerMatcher.matchOffer(deadline, offer) answers { _ =>
      // advance clock "after" match
      clock += 1.hour
      Future.successful(MatchedTaskOps(offerId, tasksWithSource))
    }

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request")
    verify(offerMatcher).matchOffer(deadline, offer)

    And("all task launches were rejected")
    assert(dummySource.accepted.isEmpty)
    assert(dummySource.rejected.toSeq.map(_._1) == tasksWithSource.map(_.op))

    And("the processor didn't try to launch the tasks")
    verify(taskLauncher, never).acceptOffer(offerId, tasksWithSource.map(_.op))

    And("no tasks where launched")
    verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = None)
    noMoreInteractions(taskLauncher)

    And("no tasks where stored")
    noMoreInteractions(taskCreationHandler)
  }

  test("match successful but first store is so slow that we are hitting storage timeout") {
    Given("an offer")
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(task => TaskOpWithSource(
      dummySource, OfferMatcher.Launch(task, MarathonTestHelper.makeTaskFromTaskInfo(task))))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    And("a cooperative taskLauncher")
    taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op).take(1)) returns true

    And("a cooperative offerMatcher")
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTaskOps(offerId, tasksWithSource))

    for (task <- tasksWithSource) {
      taskCreationHandler.created(appId, task.op.newTask) answers { args =>
        // simulate that stores are really slow
        clock += 1.hour
        Future.successful(dummyTask(appId))
      }
      taskCreationHandler.terminated(appId, task.op.taskId).asInstanceOf[Future[Unit]] returns
        Future.successful(Some(task.op.taskId))
    }

    When("processing the offer")
    Await.result(offerProcessor.processOffer(offer), 1.second)

    Then("we saw the matchOffer request and the task launch attempt for the first task")
    val firstTaskOp: Seq[TaskOp] = tasksWithSource.take(1).map(_.op)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).acceptOffer(offerId, firstTaskOp)

    And("one task launch was accepted")
    assert(dummySource.accepted.toSeq == firstTaskOp)

    And("one task launch was rejected")
    assert(dummySource.rejected.toSeq.map(_._1) == tasksWithSource.map(_.op).drop(1))

    And("the first task was stored")
    for (task <- tasksWithSource.take(1)) {
      val ordered = inOrder(taskCreationHandler)
      val op = task.op
      ordered.verify(taskCreationHandler).created(appId, op.newTask)
    }

    And("and the second task was not stored")
    noMoreInteractions(taskCreationHandler)
  }

  test("match empty => decline") {
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    offerMatcher.matchOffer(deadline, offer) returns Future.successful(MatchedTaskOps(offerId, Seq.empty))

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
  private[this] var taskCreationHandler: TaskCreationHandler = _
  private[this] var conf: OfferProcessorConfig = _

  private[this] def createProcessor(): OfferProcessor = {
    conf = new OfferProcessorConfig {}
    conf.afterInit()

    clock = ConstantClock()
    offerMatcher = mock[OfferMatcher]
    taskLauncher = mock[TaskLauncher]
    taskCreationHandler = mock[TaskCreationHandler]

    new OfferProcessorImpl(
      conf, clock, new Metrics(new MetricRegistry), offerMatcher, taskLauncher, taskCreationHandler
    )
  }

  class DummySource extends TaskOpSource {
    var rejected = Vector.empty[(TaskOp, String)]
    var accepted = Vector.empty[TaskOp]

    override def taskOpRejected(op: TaskOp, reason: String): Unit = rejected :+= op -> reason
    override def taskOpAccepted(op: TaskOp): Unit = accepted :+= op
  }
}
