package mesosphere.marathon.core.matcher.reconcile.impl

import akka.actor.{ Terminated, Cancellable }
import akka.event.EventStream
import akka.testkit.{ TestProbe, TestActorRef }
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.event.DeploymentStepSuccess
import mesosphere.marathon.state.{ Group, Residency, PathId, AppDefinition }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.upgrade.DeploymentPlan
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.concurrent.Promise
import scala.concurrent.duration._

class OffersWantedForReconciliationActorTest
    extends FunSuite with MarathonActorSupport with Mockito with GivenWhenThen with Matchers with ScalaFutures {
  test("want offers on startup but times out") {
    val f = new Fixture()

    When("starting up")
    val firstVal = f.futureOffersWanted(drop = 1)
    f.actor

    Then("offersWanted becomes true")
    firstVal.futureValue should be(true)

    And("scheduleNextCheck has been called")
    f.scheduleNextCheckCalls should be(1)

    When("the timer is expired")
    val nextVal = f.futureOffersWanted()
    f.clock += 1.hour
    f.actor ! OffersWantedForReconciliationActor.RecheckInterest

    Then("the interest stops")
    nextVal.futureValue should be(false)

    And("the timer was canceled")
    verify(f.cancellable).cancel()

    f.stop()
  }

  test("becomes interested when resident app is stopped") {
    val f = new Fixture()

    Given("an actor that has already started up and timed out")
    val firstVal = f.futureOffersWanted(drop = 2)
    f.actor
    f.clock += 1.hour
    f.actor ! OffersWantedForReconciliationActor.RecheckInterest
    firstVal.futureValue should be(false)

    reset(f.cancellable)

    When("the deployment for a resident app stops")
    val valAfterDeploymentStepSuccess = f.futureOffersWanted()
    val app = AppDefinition(PathId("/resident"), residency = Some(Residency.default))
    val plan = DeploymentPlan(original = Group.empty.copy(apps = Set(app)), target = Group.empty)
    f.eventStream.publish(DeploymentStepSuccess(plan = plan, currentStep = plan.steps.head))

    Then("there is interest for offers")
    valAfterDeploymentStepSuccess.futureValue should be(true)

    When("the timer is expired")
    val nextVal = f.futureOffersWanted()
    f.clock += 1.hour
    f.actor ! OffersWantedForReconciliationActor.RecheckInterest

    Then("the interest stops again")
    nextVal.futureValue should be(false)

    And("the timer was canceled again")
    verify(f.cancellable).cancel()

    f.stop()
  }

  class Fixture {
    lazy val reviveOffersConfig: ReviveOffersConfig = MarathonTestHelper.defaultConfig()
    lazy val clock: ConstantClock = ConstantClock()
    lazy val eventStream: EventStream = system.eventStream
    lazy val offersWanted: Subject[Boolean] = PublishSubject()

    def futureOffersWanted(drop: Int = 0) = {
      val promise = Promise[Boolean]()
      offersWanted.drop(drop).head.foreach(promise.success(_))
      promise.future
    }

    lazy val cancellable = mock[Cancellable]

    private[this] var scheduleNextCheckCalls_ = 0
    def scheduleNextCheckCalls = synchronized(scheduleNextCheckCalls_)
    def scheduleNextCheck: Cancellable = synchronized {
      scheduleNextCheckCalls_ += 1
      cancellable
    }

    lazy val actorInstance = new OffersWantedForReconciliationActor(
      reviveOffersConfig,
      clock,
      eventStream,
      offersWanted
    ) {
      override protected def scheduleNextCheck: Cancellable = Fixture.this.scheduleNextCheck
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
