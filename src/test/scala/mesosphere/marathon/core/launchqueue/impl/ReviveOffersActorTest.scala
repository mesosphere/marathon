package mesosphere.marathon
package core.launchqueue.impl

import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.ReviveOffersConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.verification.VerificationWithTimeout

import scala.concurrent.{ExecutionContext, Future}

class ReviveOffersActorTest extends AkkaUnitTest {
  "ReviveOffersActor" should {

    "suppress upon empty init state" in {
      val f = new Fixture()

      Given("no initial instances")
      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      Mockito.verify(f.instanceTracker).instancesBySpec()(any[ExecutionContext])
      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()

      f.verifyNoMoreInteractions()
    }

    "suppress upon non-empty init state" in {
      val f = new Fixture()

      Given("some initial instances")
      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(
        TestInstanceBuilder.newBuilder(f.app.id).addTaskStaged(Timestamp.now()).getInstance(),
        TestInstanceBuilder.newBuilder(f.app.id).addTaskRunning().getInstance()
      ))

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      Mockito.verify(f.instanceTracker).instancesBySpec()(any[ExecutionContext])
      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()

      f.verifyNoMoreInteractions()
    }

    "revive upon init state with Scheduled instances and suppress when Staging" in {
      val f = new Fixture()

      Given("some initial instances")
      val testInstanceScheduled = Instance.scheduled(f.app)
      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(
        TestInstanceBuilder.newBuilder(f.app.id).addTaskStaged(Timestamp.now()).getInstance(),
        testInstanceScheduled,
        TestInstanceBuilder.newBuilder(f.app.id).addTaskRunning().getInstance()
      ))

      When("the actor initializes")
      f.actorRef.start()

      Then("it will revive offers")
      Mockito.verify(f.instanceTracker).instancesBySpec()(any[ExecutionContext])
      Mockito.verify(f.driver, f.invocationTimeout).reviveOffers()

      When("the actor gets notified of the Scheduled instance becoming Staging")
      val testInstanceStaging = TestInstanceBuilder.newBuilderWithInstanceId(testInstanceScheduled.instanceId).addTaskStaged().getInstance()
      val instanceChangeEvent = InstanceChanged(
        testInstanceStaging.instanceId, testInstanceStaging.runSpecVersion, testInstanceStaging.runSpecId,
        testInstanceStaging.state.condition, testInstanceStaging)
      system.eventStream.publish(instanceChangeEvent)

      Then("suppress offers is called")
      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()

      f.verifyNoMoreInteractions()
    }

    "revive and suppress upon instance updates" in {
      val f = new Fixture()

      Given("no initial instances")
      f.instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.empty)

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      Mockito.verify(f.instanceTracker).instancesBySpec()(any[ExecutionContext])
      Mockito.verify(f.driver, f.invocationTimeout).suppressOffers()

      When("the actor gets notified of a new Scheduled instance")
      val instance1 = Instance.scheduled(f.app)
      val instance1ChangeEvent = InstanceChanged(
        instance1.instanceId, instance1.runSpecVersion, instance1.runSpecId,
        instance1.state.condition, instance1)
      system.eventStream.publish(instance1ChangeEvent)

      Then("reviveOffers is called")
      Mockito.verify(f.driver, f.invocationTimeout).reviveOffers()

      When("the actor gets notified of another Scheduled instance")
      val instance2 = Instance.scheduled(f.app)
      val instanceChangeEvent = InstanceChanged(
        instance2.instanceId, instance2.runSpecVersion, instance2.runSpecId,
        instance2.state.condition, instance2)
      system.eventStream.publish(instanceChangeEvent)

      Then("reviveOffers is called again, since we might have declined offers meanwhile")
      Mockito.verify(f.driver, times(2)).reviveOffers()

      When("the actor gets notified of the first instance becoming Staging")
      val instance1Staging = TestInstanceBuilder.newBuilderWithInstanceId(instance1.instanceId).addTaskStaged().getInstance()
      val instance1StagingEvent = InstanceChanged(
        instance1Staging.instanceId, instance1Staging.runSpecVersion, instance1Staging.runSpecId,
        instance1Staging.state.condition, instance1Staging)
      system.eventStream.publish(instance1StagingEvent)

      And("the actor gets notified of the second instance becoming Gone")
      val instance2Gone = TestInstanceBuilder.newBuilderWithInstanceId(instance2.instanceId).addTaskGone().getInstance()
      val instance2GoneEvent = InstanceChanged(
        instance2Gone.instanceId, instance2Gone.runSpecVersion, instance2Gone.runSpecId,
        instance2Gone.state.condition, instance2Gone)
      system.eventStream.publish(instance2GoneEvent)

      Then("suppress is called again")
      Mockito.verify(f.driver, times(2)).suppressOffers()

      f.verifyNoMoreInteractions()
    }
  }

  class Fixture() {
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
    val instanceTracker: InstanceTracker = mock[InstanceTracker]
    instanceTracker.instanceUpdates returns Source.empty // TODO: needed?

    val metrics: Metrics = DummyMetrics

    lazy val actorRef: TestActorRef[ReviveOffersActor] = TestActorRef[ReviveOffersActor](
      ReviveOffersActor.props(metrics, conf, system.eventStream, instanceTracker, driverHolder)
    )

    val app = AppDefinition(id = PathId("/test"))

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
