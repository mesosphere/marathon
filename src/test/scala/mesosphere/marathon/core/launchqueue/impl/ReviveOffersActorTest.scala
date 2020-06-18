package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.actor._
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import mesosphere.marathon.util.StreamHelpers
import org.apache.mesos.Protos.FrameworkInfo
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.verification.VerificationMode

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

class ReviveOffersActorTest extends AkkaUnitTest {

  val testApp = AppDefinition(id = AbsolutePathId("/test"), role = "role")

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
      val instance1 = TestInstanceBuilder.newBuilderForRunSpec(testApp).addTaskStaged(Timestamp.now()).getInstance()
      val instance2 = TestInstanceBuilder.newBuilderForRunSpec(testApp).addTaskRunning().getInstance()

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
      val snapshot = InstancesSnapshot(
        Seq(
          TestInstanceBuilder.newBuilderForRunSpec(testApp).addTaskStaged(Timestamp.now()).getInstance(),
          testInstanceScheduled,
          TestInstanceBuilder.newBuilderForRunSpec(testApp).addTaskRunning().getInstance()
        )
      )
      val f = new Fixture(instancesSnapshot = snapshot, instanceChanges = instanceChanges)

      When("the actor initializes")
      f.actorRef.start()

      Then("it will issue the initial updateFramework call with the role unsuppressed")
      f.verifyUnsuppress()

      And("it will later repeat the revive")
      // since ticks are at 500ms, and repeat happens on 2nd tick, it could take a little longer than 1 second to repeat a revive
      f.verifyExplicitRevive(Mockito.timeout(5000))

      When("the actor gets notified of the Scheduled instance becoming Staging")
      val testInstanceStaging =
        TestInstanceBuilder.newBuilderForRunSpec(testApp, instanceId = testInstanceScheduled.instanceId).addTaskStaged().getInstance()
      instanceChangesInput.offer(InstanceUpdated(testInstanceStaging, None, Nil)).futureValue

      Then("suppress offers is called")
      f.verifySuppress()
      f.verifyNoMoreInteractions()
    }

    "revive and suppress upon instance updates" in {
      Given("no initial instances")
      val (instanceChangesInput, instanceChanges) = Source.queue[InstanceChange](16, OverflowStrategy.fail).preMaterialize()
      val snapshot = InstancesSnapshot(Nil)
      val f = new Fixture(instancesSnapshot = snapshot, instanceChanges = instanceChanges)

      When("the actor initializes")
      f.actorRef.start()

      Then("it will suppress offers")
      f.verifySuppress()

      When("the actor gets notified of a new Scheduled instance")
      val instance1 = Instance.scheduled(testApp)
      instanceChangesInput.offer(InstanceUpdated(instance1, None, Nil)).futureValue

      Then("the role becomes unsuppress via updateFramework")
      f.verifyUnsuppress()

      When("the actor gets notified of another Scheduled instance")
      val instance2 = Instance.scheduled(testApp)
      instanceChangesInput.offer(InstanceUpdated(instance2, None, Nil)).futureValue

      Then("reviveOffers is called again, since we might have declined offers meanwhile")
      f.verifyExplicitRevive()

      When("the actor gets notified of the first instance becoming Staging")
      val instance1Staging =
        TestInstanceBuilder.newBuilderForRunSpec(testApp, instanceId = instance1.instanceId).addTaskStaged().getInstance()
      instanceChangesInput.offer(InstanceUpdated(instance1Staging, None, Nil)).futureValue

      And("the actor gets notified of the second instance becoming Gone")
      val instance2Gone = TestInstanceBuilder.newBuilderForRunSpec(testApp, instanceId = instance2.instanceId).addTaskGone().getInstance()
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
      val invocationTimeout: VerificationMode = Mockito.timeout(1000).atLeastOnce()
  ) {

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
      ReviveOffersActor.props(
        metrics,
        Future.successful(initialFrameworkInfo),
        defaultRole,
        minReviveOffersInterval = 500.millis,
        instanceUpdates = instanceUpdates,
        rateLimiterUpdates = delayUpdates,
        driverHolder = driverHolder,
        enableSuppress = enableSuppress
      )
    )

    def verifyUnsuppress(): Unit = {
      import org.mockito.Matchers.{eq => mEq}
      Mockito.verify(driver, invocationTimeout).updateFramework(any, mEq(Nil.asJava))
    }
    def verifySuppress(): Unit = {
      import org.mockito.Matchers.{eq => mEq}
      Mockito.verify(driver, invocationTimeout).updateFramework(any, mEq(Seq(defaultRole).asJava))
    }
    def verifyExplicitRevive(timeout: VerificationMode = invocationTimeout): Unit = {
      Mockito.verify(driver, timeout).reviveOffers(Set(defaultRole).asJava)
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
