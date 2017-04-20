package mesosphere.marathon
package core.launcher.impl

import akka.Done
import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.{ InstanceOp, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpSource, InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfoPlaceholder
import mesosphere.marathon.core.task.tracker.InstanceCreationHandler
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.util.NoopSourceQueue
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class OfferProcessorImplTest extends UnitTest {
  private[this] val offer = MarathonTestHelper.makeBasicOffer().build()
  private[this] val offerId = offer.getId
  private val appId: PathId = PathId("/testapp")
  private[this] val instanceId1 = Instance.Id.forRunSpec(appId)
  private[this] val instanceId2 = Instance.Id.forRunSpec(appId)
  private[this] val taskInfo1 = MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(instanceId1, None)).build()
  private[this] val taskInfo2 = MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(instanceId2, None)).build()
  private[this] val instance1 = TestInstanceBuilder.newBuilderWithInstanceId(instanceId1).addTaskWithBuilder().taskFromTaskInfo(taskInfo1).build().getInstance()
  private[this] val instance2 = TestInstanceBuilder.newBuilderWithInstanceId(instanceId2).addTaskWithBuilder().taskFromTaskInfo(taskInfo2).build().getInstance()
  private[this] val task1: Task.LaunchedEphemeral = instance1.appTask
  private[this] val task2: Task.LaunchedEphemeral = instance2.appTask

  private[this] val tasks = Seq((taskInfo1, task1, instance1), (taskInfo2, task2, instance2))

  case class Fixture(
      conf: OfferProcessorConfig = new OfferProcessorConfig { verify() },
      clock: ConstantClock = ConstantClock(),
      offerMatcher: OfferMatcher = mock[OfferMatcher],
      taskLauncher: TaskLauncher = mock[TaskLauncher],
      taskCreationHandler: InstanceCreationHandler = mock[InstanceCreationHandler]) {
    val offerProcessor = new OfferProcessorImpl(
      conf, clock, offerMatcher, taskLauncher, taskCreationHandler,
      NoopSourceQueue()
    )
  }

  object f {
    import org.apache.mesos.{ Protos => Mesos }
    val launch = new InstanceOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task.LaunchedEphemeral, _: Instance)
    val launchWithOldTask = new InstanceOpFactoryHelper(Some("principal"), Some("role")).launchOnReservation _
  }

  class DummySource extends InstanceOpSource {
    var rejected = Vector.empty[(InstanceOp, String)]
    var accepted = Vector.empty[InstanceOp]

    override def instanceOpRejected(op: InstanceOp, reason: String): Unit = rejected :+= op -> reason
    override def instanceOpAccepted(op: InstanceOp): Unit = accepted :+= op
  }

  "OfferProcessorImpl" should {
    "match successful, launch tasks successful" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map(task => InstanceOpWithSource(dummySource, f.launch(task._1, task._2, task._3)))

      val now = clock.now()
      val deadline: Timestamp = now + 1.second

      And("a cooperative offerMatcher and taskTracker")
      offerMatcher.matchOffer(now, deadline, offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      for (task <- tasks) {
        val stateOp = InstanceUpdateOperation.LaunchEphemeral(task._3)
        taskCreationHandler.created(stateOp) returns Future.successful(Done)
      }

      And("a working taskLauncher")
      val ops: Seq[InstanceOp] = tasksWithSource.map(_.op)
      taskLauncher.acceptOffer(offerId, ops) returns true

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the offerMatch request and the task launches")
      verify(offerMatcher).matchOffer(now, deadline, offer)
      verify(taskLauncher).acceptOffer(offerId, ops)

      And("all task launches have been accepted")
      assert(dummySource.rejected.isEmpty)
      assert(dummySource.accepted == tasksWithSource.map(_.op))

      And("the tasks have been stored")
      for (task <- tasksWithSource) {
        val ordered = inOrder(taskCreationHandler)
        ordered.verify(taskCreationHandler).created(task.op.stateOp)
      }
    }

    "match successful, launch tasks unsuccessful" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map(task => InstanceOpWithSource(dummySource, f.launch(task._1, task._2, task._3)))

      val now = clock.now()
      val deadline: Timestamp = now + 1.second
      And("a cooperative offerMatcher and taskTracker")
      offerMatcher.matchOffer(now, deadline, offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      for (task <- tasksWithSource) {
        val op = task.op
        taskCreationHandler.created(op.stateOp) returns Future.successful(Done)
        taskCreationHandler.terminated(InstanceUpdateOperation.ForceExpunge(op.stateOp.instanceId)) returns Future.successful(Done)
      }

      And("a dysfunctional taskLauncher")
      taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op)) returns false

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the matchOffer request and the task launch attempt")
      verify(offerMatcher).matchOffer(now, deadline, offer)
      verify(taskLauncher).acceptOffer(offerId, tasksWithSource.map(_.op))

      And("all task launches were rejected")
      assert(dummySource.accepted.isEmpty)
      assert(dummySource.rejected.map(_._1) == tasksWithSource.map(_.op))

      And("the tasks where first stored and then expunged again")
      for (task <- tasksWithSource) {
        val ordered = inOrder(taskCreationHandler)
        val op = task.op
        ordered.verify(taskCreationHandler).created(op.stateOp)
        ordered.verify(taskCreationHandler).terminated(InstanceUpdateOperation.ForceExpunge(op.stateOp.instanceId))
      }
    }

    "match successful, launch tasks unsuccessful, revert to prior task state" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map {
        case (taskInfo, _, _) =>
          val dummyInstance = TestInstanceBuilder.newBuilder(appId).addTaskResidentReserved().getInstance()
          val updateOperation = InstanceUpdateOperation.LaunchOnReservation(
            instanceId = dummyInstance.instanceId,
            runSpecVersion = clock.now(),
            timestamp = clock.now(),
            status = Task.Status(clock.now(), condition = Condition.Running, networkInfo = NetworkInfoPlaceholder()),
            hostPorts = Seq.empty)
          val launch = f.launchWithOldTask(
            taskInfo,
            updateOperation,
            dummyInstance
          )
          InstanceOpWithSource(dummySource, launch)
      }

      val now = clock.now()
      val deadline: Timestamp = now + 1.second
      And("a cooperative offerMatcher and taskTracker")
      offerMatcher.matchOffer(now, deadline, offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      for (task <- tasksWithSource) {
        val op = task.op
        taskCreationHandler.created(op.stateOp) returns Future.successful(Done)
        taskCreationHandler.created(InstanceUpdateOperation.Revert(op.oldInstance.get)) returns Future.successful(Done)
      }

      And("a dysfunctional taskLauncher")
      taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op)) returns false

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the matchOffer request and the task launch attempt")
      verify(offerMatcher).matchOffer(now, deadline, offer)
      verify(taskLauncher).acceptOffer(offerId, tasksWithSource.map(_.op))

      And("all task launches were rejected")
      assert(dummySource.accepted.isEmpty)
      assert(dummySource.rejected.map(_._1) == tasksWithSource.map(_.op))

      And("the tasks where first stored and then expunged again")
      for (task <- tasksWithSource) {
        val op = task.op
        val ordered = inOrder(taskCreationHandler)
        ordered.verify(taskCreationHandler).created(op.stateOp)
        ordered.verify(taskCreationHandler).created(InstanceUpdateOperation.Revert(op.oldInstance.get))
      }
    }

    "match successful but very slow so that we are hitting storage timeout" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map(task => InstanceOpWithSource(dummySource, f.launch(task._1, task._2, task._3)))

      val now = clock.now()
      val deadline: Timestamp = now + 1.second
      And("a cooperative offerMatcher that takes really long")
      offerMatcher.matchOffer(now, deadline, offer) answers { _ =>
        // advance clock "after" match
        clock += 1.hour
        Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      }

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the matchOffer request")
      verify(offerMatcher).matchOffer(now, deadline, offer)

      And("all task launches were rejected")
      assert(dummySource.accepted.isEmpty)
      assert(dummySource.rejected.map(_._1) == tasksWithSource.map(_.op))

      And("the processor didn't try to launch the tasks")
      verify(taskLauncher, never).acceptOffer(offerId, tasksWithSource.map(_.op))

      And("no tasks where launched")
      verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = None)
      noMoreInteractions(taskLauncher)

      And("no tasks where stored")
      noMoreInteractions(taskCreationHandler)
    }

    "match successful but first store is so slow that we are hitting storage timeout" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map(task => InstanceOpWithSource(dummySource, f.launch(task._1, task._2, task._3)))

      val now = clock.now()
      val deadline: Timestamp = now + 1.second
      And("a cooperative taskLauncher")
      taskLauncher.acceptOffer(offerId, tasksWithSource.take(1).map(_.op)) returns true

      And("a cooperative offerMatcher")
      offerMatcher.matchOffer(now, deadline, offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))

      for (task <- tasksWithSource) {
        taskCreationHandler.created(task.op.stateOp) answers { args =>
          // simulate that stores are really slow
          clock += 1.hour
          Future.successful(Done)
        }
        taskCreationHandler.terminated(InstanceUpdateOperation.ForceExpunge(task.op.instanceId)) returns Future.successful(Done)
      }

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the matchOffer request and the task launch attempt for the first task")
      val firstTaskOp: Seq[InstanceOp] = tasksWithSource.take(1).map(_.op)

      verify(offerMatcher).matchOffer(now, deadline, offer)
      verify(taskLauncher).acceptOffer(offerId, firstTaskOp)

      And("one task launch was accepted")
      assert(dummySource.accepted == firstTaskOp)

      And("one task launch was rejected")
      assert(dummySource.rejected.map(_._1) == tasksWithSource.drop(1).map(_.op))

      And("the first task was stored")
      for (task <- tasksWithSource.take(1)) {
        val ordered = inOrder(taskCreationHandler)
        val op = task.op
        ordered.verify(taskCreationHandler).created(op.stateOp)
      }

      And("and the second task was not stored")
      noMoreInteractions(taskCreationHandler)
    }

    "match empty => decline" in new Fixture {
      val now = clock.now()
      val deadline: Timestamp = now + 1.second
      offerMatcher.matchOffer(now, deadline, offer) returns Future.successful(MatchedInstanceOps(offerId, Seq.empty))

      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      verify(offerMatcher).matchOffer(now, deadline, offer)
      verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = Some(conf.declineOfferDuration()))
    }

    "match crashed => decline" in new Fixture {
      val now = clock.now()
      val deadline: Timestamp = now + 1.second
      offerMatcher.matchOffer(now, deadline, offer) returns Future.failed(new RuntimeException("failed matching"))

      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      verify(offerMatcher).matchOffer(now, deadline, offer)
      verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = None)
    }

  }
}
