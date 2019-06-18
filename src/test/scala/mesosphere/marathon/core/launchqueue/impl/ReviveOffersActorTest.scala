package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{InstanceChange, InstancesSnapshot}
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.launchqueue.ReviveOffersConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}
import mesosphere.marathon.util.StreamHelpers
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.verification.VerificationWithTimeout

class ReviveOffersActorTest extends AkkaUnitTest {

  val testApp = AppDefinition(id = PathId("/test"))

  "ReviveOffersActor" should {

    "suppress upon empty init state" in {
      val f = new Fixture()

      Given("no initial instances")

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()

      f.verifyNoMoreInteractions()
    }

    "suppress upon non-empty init state" in {
      val instance1 = TestInstanceBuilder.newBuilder(testApp.id).addTaskStaged(Timestamp.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(testApp.id).addTaskRunning().getInstance()

      Given("some initial instances")
      val f = new Fixture(instanceSnapshot = InstancesSnapshot(Seq(instance1, instance2)))

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()

      f.verifyNoMoreInteractions()
    }
    //
    //    "revive upon init state with Scheduled instances and suppress when Staging" in {
    //      val f = new Fixture()
    //
    //      Given("some initial instances")
    //      val testInstanceScheduled = Instance.scheduled(f.app)
    //      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(
    //        TestInstanceBuilder.newBuilder(f.app.id).addTaskStaged(Timestamp.now()).getInstance(),
    //        testInstanceScheduled,
    //        TestInstanceBuilder.newBuilder(f.app.id).addTaskRunning().getInstance()
    //      ))
    //
    //      When("the actor initializes")
    //      f.actorRef.start()
    //
    //      Then("it will revive offers")
    //      Mockito.verify(f.instanceTracker).instancesBySpec()(any[ExecutionContext])
    //      Mockito.verify(f.driver, f.invocationTimeout).reviveOffers()
    //
    //      When("the actor gets notified of the Scheduled instance becoming Staging")
    //      val testInstanceStaging = TestInstanceBuilder.newBuilderWithInstanceId(testInstanceScheduled.instanceId).addTaskStaged().getInstance()
    //      val instanceChangeEvent = InstanceChangedEventsGenerator.updatedCondition(testInstanceStaging)
    //      system.eventStream.publish(instanceChangeEvent)
    //
    //      Then("suppress offers is called")
    //      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()
    //
    //      f.verifyNoMoreInteractions()
    //    }
    //
    //    "revive and suppress upon instance updates" in {
    //      val f = new Fixture()
    //
    //      Given("no initial instances")
    //      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.empty)
    //
    //      When("the actor initializes")
    //      f.actorRef.start()
    //
    //      Then("it will suppress offers")
    //      Mockito.verify(f.instanceTracker).instancesBySpec()(any[ExecutionContext])
    //      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()
    //
    //      When("the actor gets notified of a new Scheduled instance")
    //      val instance1 = Instance.scheduled(f.app)
    //      val instance1ChangeEvent = InstanceChangedEventsGenerator.updatedCondition(instance1)
    //      system.eventStream.publish(instance1ChangeEvent)
    //
    //      Then("reviveOffers is called")
    //      Mockito.verify(f.driver, f.invocationTimeout).reviveOffers()
    //
    //      When("the actor gets notified of another Scheduled instance")
    //      val instance2 = Instance.scheduled(f.app)
    //      val instance2ChangeEvent = InstanceChangedEventsGenerator.updatedCondition(instance2)
    //      system.eventStream.publish(instance2ChangeEvent)
    //
    //      Then("reviveOffers is called again, since we might have declined offers meanwhile")
    //      Mockito.verify(f.driver, times(2)).reviveOffers()
    //
    //      When("the actor gets notified of the first instance becoming Staging")
    //      val instance1Staging = TestInstanceBuilder.newBuilderWithInstanceId(instance1.instanceId).addTaskStaged().getInstance()
    //      val instance1StagingEvent = InstanceChangedEventsGenerator.updatedCondition(instance1Staging)
    //      system.eventStream.publish(instance1StagingEvent)
    //
    //      And("the actor gets notified of the second instance becoming Gone")
    //      val instance2Gone = TestInstanceBuilder.newBuilderWithInstanceId(instance2.instanceId).addTaskGone().getInstance()
    //      val instance2GoneEvent = InstanceChangedEventsGenerator.updatedCondition(instance2Gone)
    //      system.eventStream.publish(instance2GoneEvent)
    //
    //      Then("suppress is called again")
    //      Mockito.verify(f.driver, times(2)).suppressOffers()
    //
    //      f.verifyNoMoreInteractions()
    //    }
  }

  class Fixture(
      instanceSnapshot: InstancesSnapshot = InstancesSnapshot(Nil),
      instanceChanges: Source[InstanceChange, NotUsed] = StreamHelpers.sourceNever,
      delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed] = StreamHelpers.sourceNever) {

    val instanceUpdates: InstanceTracker.InstanceUpdates = Source.single(instanceSnapshot -> instanceChanges)
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val conf: ReviveOffersConfig = {
      new ReviveOffersConfig {
        verify()
      }
    }
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val driverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(driver)
      holder
    }
    val metrics: Metrics = DummyMetrics

    lazy val actorRef: TestActorRef[ReviveOffersActor] = TestActorRef[ReviveOffersActor](
      ReviveOffersActor.props(metrics, conf, instanceUpdates, delayUpdates, driverHolder)
    )

    val invocationTimeout: VerificationWithTimeout = Mockito.timeout(1000)

    def verifyNoMoreInteractions(): Unit = {
      def killActorAndWaitForDeath(): Terminated = {
        actorRef ! PoisonPill
        val deathWatch = TestProbe()
        deathWatch.watch(actorRef)
        deathWatch.expectMsgClass(classOf[Terminated])
      }

      killActorAndWaitForDeath()

      Mockito.verifyNoMoreInteractions(driver)
    }
  }

}
