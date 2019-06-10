package mesosphere.marathon
package core.matcher.reconcile.impl

import java.util.UUID

import akka.actor.Terminated
import akka.event.EventStream
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.event.DeploymentStepSuccess
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.state._
import mesosphere.marathon.test.{GroupCreation, MarathonTestHelper}
import mesosphere.marathon.core.deployment.DeploymentPlan
import org.scalatest.concurrent.Eventually
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.concurrent.Promise
import scala.concurrent.duration._

class OffersWantedForReconciliationActorTest extends AkkaUnitTest with GroupCreation with Eventually {
  "OffersWantedForReconciliationActor" should {
    "want offers on startup but times out" in {
      val f = new Fixture()

      When("starting up")
      val firstVal = f.futureOffersWanted(drop = 1)
      f.actor // start the actor

      Then("offersWanted becomes true")
      firstVal.futureValue should be(true)

      And("scheduleNextCheck has been called")
      f.cancelUUIDs.length should be(1)

      When("the cancellation timer runs")
      val nextVal = f.futureOffersWanted()
      f.clock += 1.hour
      f.actor ! OffersWantedForReconciliationActor.CancelInterestInOffers(f.cancelUUIDs.last)

      Then("the interest stops")
      nextVal.futureValue should be(false)

      f.stop()
    }

    "becomes interested when resident app is stopped" in {
      val f = new Fixture()

      Given("an actor that has already started up and timed out")
      val firstVal = f.futureOffersWanted(drop = 2)
      f.actor // start the actor
      eventually { f.cancelUUIDs.length shouldBe 1 }
      f.actor ! OffersWantedForReconciliationActor.CancelInterestInOffers(f.cancelUUIDs.last)
      firstVal.futureValue should be(false)

      When("the deployment for a resident app stops")
      val valAfterDeploymentStepSuccess = f.futureOffersWanted()
      val vol = VolumeWithMount(
        volume = PersistentVolume(name = None, persistent = PersistentVolumeInfo(123)),
        mount = VolumeMount(volumeName = None, mountPath = "bar", readOnly = false))
      val zero = UpgradeStrategy(0, 0)
      val app = AppDefinition(
        PathId("/resident"),
        cmd = Some("sleep"),
        container = Some(Container.Mesos(Seq(vol))),
        upgradeStrategy = zero,
        unreachableStrategy = UnreachableDisabled)
      val plan = DeploymentPlan(original = createRootGroup(apps = Map(app.id -> app)), target = createRootGroup())
      f.eventStream.publish(DeploymentStepSuccess(plan = plan, currentStep = plan.steps.head))

      Then("there is interest for offers")
      valAfterDeploymentStepSuccess.futureValue should be(true)
      eventually { f.cancelUUIDs.length shouldBe 2 }

      When("the timer is expired")
      val nextVal = f.futureOffersWanted()
      f.actor ! OffersWantedForReconciliationActor.CancelInterestInOffers(f.cancelUUIDs.last)

      Then("the interest stops again")
      nextVal.futureValue should be(false)

      f.stop()
    }
  }

  class Fixture {
    lazy val reviveOffersConfig: ReviveOffersConfig = MarathonTestHelper.defaultConfig()
    lazy val clock: SettableClock = new SettableClock()
    lazy val eventStream: EventStream = system.eventStream
    lazy val offersWanted: Subject[Boolean] = PublishSubject()

    def futureOffersWanted(drop: Int = 0) = {
      val promise = Promise[Boolean]()
      offersWanted.drop(drop).head.foreach(promise.success(_))
      promise.future
    }

    var cancelUUIDs: List[UUID] = Nil
    def scheduleNextCheck: UUID = synchronized {
      val newUUID = UUID.randomUUID()
      cancelUUIDs = cancelUUIDs :+ newUUID
      newUUID
    }

    lazy val actorInstance = new OffersWantedForReconciliationActor(
      reviveOffersConfig,
      clock,
      eventStream,
      offersWanted
    ) {
      override protected def scheduleCancelInterestInOffers: UUID = Fixture.this.scheduleNextCheck
    }
    lazy val actor = TestActorRef(actorInstance)

    def stop(): Unit = {
      val probe = TestProbe()
      probe.watch(actor)
      actor.stop()
      probe.expectMsgClass(classOf[Terminated])
    }
  }
}
