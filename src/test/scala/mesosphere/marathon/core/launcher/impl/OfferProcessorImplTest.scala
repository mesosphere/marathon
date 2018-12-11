package mesosphere.marathon
package core.launcher.impl

import akka.Done
import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launcher.{InstanceOp, OfferProcessorConfig, TaskLauncher}
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{InstanceOpSource, InstanceOpWithSource, MatchedInstanceOps}
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.test.MarathonTestHelper
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
  private[this] val taskInfo1 = MarathonTestHelper.makeOneCPUTask(Task.Id(instanceId1)).build()
  private[this] val taskInfo2 = MarathonTestHelper.makeOneCPUTask(Task.Id(instanceId2)).build()
  private[this] val instance1 = TestInstanceBuilder.newBuilderWithInstanceId(instanceId1).addTaskWithBuilder().taskFromTaskInfo(taskInfo1).build().getInstance()
  private[this] val instance2 = TestInstanceBuilder.newBuilderWithInstanceId(instanceId2).addTaskWithBuilder().taskFromTaskInfo(taskInfo2).build().getInstance()
  private[this] val task1: Task = instance1.appTask
  private[this] val task2: Task = instance2.appTask

  private[this] val tasks = Seq((taskInfo1, task1, instance1), (taskInfo2, task2, instance2))
  private[this] val arbitraryInstanceUpdateEffect = InstanceUpdateEffect.Noop(instanceId1)

  case class Fixture(
      conf: OfferProcessorConfig = new OfferProcessorConfig { verify() },
      clock: SettableClock = new SettableClock(),
      offerMatcher: OfferMatcher = mock[OfferMatcher],
      taskLauncher: TaskLauncher = mock[TaskLauncher],
      instanceTracker: InstanceTracker = mock[InstanceTracker]) {
    val metrics = DummyMetrics
    val offerProcessor = new OfferProcessorImpl(
      metrics, conf, offerMatcher, taskLauncher, instanceTracker)
  }

  object f {
    import org.apache.mesos.{Protos => Mesos}
    val metrics = DummyMetrics
    val provision = new InstanceOpFactoryHelper(metrics, Some("principal"), Some("role"))
      .provision(_: Mesos.TaskInfo, _: InstanceUpdateOperation.Provision)
    val launchWithNewTask = new InstanceOpFactoryHelper(metrics, Some("principal"), Some("role"))
      .launchOnReservation(_: Mesos.TaskInfo, _: InstanceUpdateOperation.Provision, _: Instance)
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
      val tasksWithSource = tasks.map {
        case (taskInfo, task, Instance(instanceId, agentInfo, _, tasksMap, runSpec, _)) =>
          val stateOp = InstanceUpdateOperation.Provision(instanceId, agentInfo.get, runSpec, tasksMap, clock.now())
          InstanceOpWithSource(dummySource, f.provision(taskInfo, stateOp))
      }

      And("a cooperative offerMatcher and taskTracker")
      offerMatcher.matchOffer(offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      for ((_, _, Instance(instanceId, agentInfo, _, tasksMap, runSpec, _)) <- tasks) {
        val stateOp = InstanceUpdateOperation.Provision(instanceId, agentInfo.get, runSpec, tasksMap, clock.now())
        instanceTracker.process(stateOp) returns Future.successful(arbitraryInstanceUpdateEffect)
      }

      And("a working taskLauncher")
      val ops: Seq[InstanceOp] = tasksWithSource.map(_.op)
      taskLauncher.acceptOffer(offerId, ops) returns true

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the offerMatch request and the task launches")
      verify(offerMatcher).matchOffer(offer)
      verify(taskLauncher).acceptOffer(offerId, ops)

      And("all task launches have been accepted")
      assert(dummySource.rejected.isEmpty)
      assert(dummySource.accepted == tasksWithSource.map(_.op))

      And("the tasks have been stored")
      for (task <- tasksWithSource) {
        val ordered = inOrder(instanceTracker)
        ordered.verify(instanceTracker).process(task.op.stateOp)
      }
    }

    "match successful, launch tasks unsuccessful" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map {
        case (taskInfo, task, Instance(instanceId, agentInfo, _, tasksMap, runSpec, _)) =>
          val stateOp = InstanceUpdateOperation.Provision(instanceId, agentInfo.get, runSpec, tasksMap, clock.now())
          val op = f.provision(taskInfo, stateOp)
          InstanceOpWithSource(dummySource, op)
      }

      And("a cooperative offerMatcher and taskTracker")
      offerMatcher.matchOffer(offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      for (task <- tasksWithSource) {
        val op = task.op
        instanceTracker.process(op.stateOp) returns Future.successful(arbitraryInstanceUpdateEffect)
        instanceTracker.forceExpunge(op.stateOp.instanceId) returns Future.successful(Done)
      }

      And("a dysfunctional taskLauncher")
      taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op)) returns false

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the matchOffer request and the task launch attempt")
      verify(offerMatcher).matchOffer(offer)
      verify(taskLauncher).acceptOffer(offerId, tasksWithSource.map(_.op))

      And("all task launches were rejected")
      assert(dummySource.accepted.isEmpty)
      assert(dummySource.rejected.map(_._1) == tasksWithSource.map(_.op))

      And("the tasks where first stored and then expunged again")
      for (task <- tasksWithSource) {
        val ordered = inOrder(instanceTracker)
        val op = task.op
        ordered.verify(instanceTracker).process(op.stateOp)
        ordered.verify(instanceTracker).forceExpunge(op.stateOp.instanceId)
      }
    }

    "match successful, launch tasks unsuccessful, revert to prior task state" in new Fixture {
      Given("an offer")
      val dummySource = new DummySource
      val tasksWithSource = tasks.map {
        case (taskInfo, _, _) =>
          val dummyInstance = TestInstanceBuilder.scheduledWithReservation(AppDefinition(appId))
          val taskId = Task.Id.parse(taskInfo.getTaskId)
          val app = AppDefinition(appId)
          val launch = f.launchWithNewTask(
            taskInfo,
            InstanceUpdateOperation.Provision(
              dummyInstance.instanceId, AgentInfoPlaceholder(), app, Tasks.provisioned(taskId, NetworkInfoPlaceholder(), app.version, clock.now()), clock.now()
            ),
            dummyInstance
          )
          InstanceOpWithSource(dummySource, launch)
      }

      And("a cooperative offerMatcher and taskTracker")
      offerMatcher.matchOffer(offer) returns Future.successful(MatchedInstanceOps(offerId, tasksWithSource))
      for (task <- tasksWithSource) {
        val op = task.op
        instanceTracker.process(op.stateOp) returns Future.successful(arbitraryInstanceUpdateEffect)
        instanceTracker.revert(op.oldInstance.get) returns Future.successful(Done)
      }

      And("a dysfunctional taskLauncher")
      taskLauncher.acceptOffer(offerId, tasksWithSource.map(_.op)) returns false

      When("processing the offer")
      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      Then("we saw the matchOffer request and the task launch attempt")
      verify(offerMatcher).matchOffer(offer)
      verify(taskLauncher).acceptOffer(offerId, tasksWithSource.map(_.op))

      And("all task launches were rejected")
      assert(dummySource.accepted.isEmpty)
      assert(dummySource.rejected.map(_._1) == tasksWithSource.map(_.op))

      And("the tasks where first stored and then expunged again")
      for (task <- tasksWithSource) {
        val op = task.op
        val ordered = inOrder(instanceTracker)
        ordered.verify(instanceTracker).process(op.stateOp)
        ordered.verify(instanceTracker).revert(op.oldInstance.get)
      }
    }

    "match empty => decline" in new Fixture {
      offerMatcher.matchOffer(offer) returns Future.successful(MatchedInstanceOps(offerId, Seq.empty))

      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      verify(offerMatcher).matchOffer(offer)
      verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = Some(conf.declineOfferDuration()))
    }

    "match crashed => decline" in new Fixture {
      offerMatcher.matchOffer(offer) returns Future.failed(new RuntimeException("failed matching"))

      offerProcessor.processOffer(offer).futureValue(Timeout(1.second))

      verify(offerMatcher).matchOffer(offer)
      verify(taskLauncher).declineOffer(offerId, refuseMilliseconds = None)
    }

  }
}
