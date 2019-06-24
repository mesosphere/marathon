package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.actor._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}
import mesosphere.marathon.util.StreamHelpers
import org.apache.mesos.Protos.FrameworkInfo
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.verification.VerificationWithTimeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.JavaConverters._

class ReviveOffersActorTest extends AkkaUnitTest {

  val testApp = AppDefinition(id = PathId("/test"), role = "*")

  "ReviveOffersActor" should {

    "suppress upon empty init state" in {
      val f = new Fixture()

      Given("no initial instances")

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      f.verifySuppress()

      f.verifyNoMoreInteractions()
    }

    "suppress upon non-empty init state" in {
      val instance1 = TestInstanceBuilder.newBuilder(testApp.id).addTaskStaged(Timestamp.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(testApp.id).addTaskRunning().getInstance()

      Given("some initial instances")
      val f = new Fixture(instancesSnapshot = InstancesSnapshot(Seq(instance1, instance2)))

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      f.verifySuppress()

      f.verifyNoMoreInteractions()
    }

    "revive upon init state with Scheduled instances and suppress when Staging" in {
      Given("some initial instances")
      val (instanceChangesInput, instanceChanges) = Source.queue[InstanceChange](16, OverflowStrategy.fail).preMaterialize()
      val testInstanceScheduled = Instance.scheduled(testApp)
      val snapshot = InstancesSnapshot(Seq(
        TestInstanceBuilder.newBuilder(testApp.id).addTaskStaged(Timestamp.now()).getInstance(),
        testInstanceScheduled,
        TestInstanceBuilder.newBuilder(testApp.id).addTaskRunning().getInstance()))
      val f = new Fixture(
        instancesSnapshot = snapshot,
        instanceChanges = instanceChanges)

      When("the actor initializes")
      f.actorRef.start()

      Then("it will revive offers 3 times")
      Mockito.verify(f.driver, f.invocationTimeout.times(3)).reviveOffers(Seq(f.defaultRole).asJava)

      When("the actor gets notified of the Scheduled instance becoming Staging")
      val testInstanceStaging = TestInstanceBuilder.newBuilderWithInstanceId(testInstanceScheduled.instanceId).addTaskStaged().getInstance()
      instanceChangesInput.offer(InstanceUpdated(testInstanceStaging, None, Nil)).futureValue

      Then("suppress offers is called")
      f.verifySuppress()
      f.verifyNoMoreInteractions()
    }

    "revive and suppress upon instance updates" in {
      Given("no initial instances")
      val (instanceChangesInput, instanceChanges) = Source.queue[InstanceChange](16, OverflowStrategy.fail).preMaterialize()
      val snapshot = InstancesSnapshot(Nil)
      val f = new Fixture(
        instancesSnapshot = snapshot,
        instanceChanges = instanceChanges)

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      f.verifySuppress()

      When("the actor gets notified of a new Scheduled instance")
      val instance1 = Instance.scheduled(testApp)
      instanceChangesInput.offer(InstanceUpdated(instance1, None, Nil)).futureValue

      Then("reviveOffers is called")
      f.verifyRevive()

      When("the actor gets notified of another Scheduled instance")
      val instance2 = Instance.scheduled(testApp)
      instanceChangesInput.offer(InstanceUpdated(instance2, None, Nil)).futureValue

      Then("reviveOffers is called again, since we might have declined offers meanwhile")
      f.verifyRevive()

      When("the actor gets notified of the first instance becoming Staging")
      val instance1Staging = TestInstanceBuilder.newBuilderWithInstanceId(instance1.instanceId).addTaskStaged().getInstance()
      instanceChangesInput.offer(InstanceUpdated(instance1Staging, None, Nil)).futureValue

      And("the actor gets notified of the second instance becoming Gone")
      val instance2Gone = TestInstanceBuilder.newBuilderWithInstanceId(instance2.instanceId).addTaskGone().getInstance()
      instanceChangesInput.offer(InstanceUpdated(instance2Gone, None, Nil)).futureValue

      Then("suppress is called again")
      f.verifySuppress()
    }
  }

  class Fixture(
      instancesSnapshot: InstancesSnapshot = InstancesSnapshot(Nil),
      instanceChanges: Source[InstanceChange, NotUsed] = StreamHelpers.sourceNever,
      delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed] = StreamHelpers.sourceNever,
      val defaultRole: String = "role",
      enableSuppress: Boolean = true,
      val invocationTimeout: VerificationWithTimeout = Mockito.timeout(1000)) {

    val instanceUpdates: InstanceTracker.InstanceUpdates = Source.single(instancesSnapshot -> instanceChanges)
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val driverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(driver)
      holder
    }
    val metrics: Metrics = DummyMetrics
    val initialFrameworkInfo = FrameworkInfo.newBuilder().setUser("test").setName("test").build

    lazy val actorRef: TestActorRef[ReviveOffersActor] = TestActorRef[ReviveOffersActor](
      ReviveOffersActor.props(metrics, Future.successful(initialFrameworkInfo), defaultRole, minReviveOffersInterval = 100.millis,
        instanceUpdates = instanceUpdates, rateLimiterUpdates = delayUpdates, driverHolder = driverHolder, enableSuppress = enableSuppress)
    )

    def verifySuppress(): Unit = {
      import org.mockito.Matchers.{eq => mEq}
      Mockito.verify(driver, invocationTimeout).updateFramework(any, mEq(Seq(defaultRole).asJava))
    }
    def verifyRevive(): Unit = {
      Mockito.verify(driver, invocationTimeout).reviveOffers(Seq(defaultRole).asJava)
    }

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
