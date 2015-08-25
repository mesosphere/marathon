package mesosphere.marathon.core.flow.impl

import akka.actor._
import akka.testkit.{ TestProbe, TestActorRef }
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.event.{ SchedulerRegisteredEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec }
import org.apache.mesos.SchedulerDriver
import org.mockito.{ Matchers, ArgumentMatcher, Mockito }
import org.mockito.internal.matchers.{ Matches, CapturesArguments }
import org.scalatest.GivenWhenThen
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject
import scala.concurrent.duration._

class ReviveOffersActorTest extends MarathonSpec with GivenWhenThen {
  test("do not do anything") {
    When("the actor starts")
    actorRef.start()

    Then("there are no surprising interactions")
    verifyNoMoreInteractions()
  }

  test("revive on first OffersWanted(true)") {
    Given("a started actor")
    actorRef.start()

    When("the actor gets notified of wanted offers")
    offersWanted.onNext(true)

    Then("reviveOffers is called")
    Mockito.verify(driver, Mockito.timeout(1000)).reviveOffers()
    verifyNoMoreInteractions()
  }

  test(s"revive if offers wanted and we receive explicit reviveOffers") {
    Given("a started actor that wants offers")
    actorRef.start()
    offersWanted.onNext(true)
    Mockito.reset(driver)
    clock += 10.seconds

    When("we explicitly reviveOffers")
    val offerReviver = new OfferReviverDelegate(actorRef)
    offerReviver.reviveOffers()

    Then("reviveOffers is called directly")
    Mockito.verify(driver, Mockito.timeout(1000)).reviveOffers()
    verifyNoMoreInteractions()
  }

  for (
    reviveEvent <- Seq(
      SchedulerReregisteredEvent("somemaster"),
      SchedulerRegisteredEvent("frameworkid", "somemaster"),
      ReviveOffersActor.TimedCheck
    )
  ) {
    test(s"revive if offers wanted and we receive $reviveEvent") {
      Given("a started actor that wants offers")
      actorRef.start()
      offersWanted.onNext(true)
      Mockito.reset(driver)
      clock += 10.seconds

      When(s"the actor receives an $reviveEvent message")
      actorRef ! reviveEvent

      Then("reviveOffers is called directly")
      Mockito.verify(driver, Mockito.timeout(1000)).reviveOffers()
      verifyNoMoreInteractions()
    }
  }

  for (
    reviveEvent <- Seq(
      SchedulerReregisteredEvent("somemaster"),
      SchedulerRegisteredEvent("frameworkid", "somemaster"),
      ReviveOffersActor.TimedCheck
    )
  ) {
    test(s"DO NOT revive if offers NOT wanted and we receive $reviveEvent") {
      Given("a started actor that wants offers")
      actorRef.start()
      offersWanted.onNext(false)
      Mockito.reset(driver)
      clock += 10.seconds

      When(s"the actor receives an $reviveEvent message")
      actorRef ! reviveEvent

      Then("reviveOffers is NOT called directly")
      verifyNoMoreInteractions()
    }
  }

  test("only one revive for two fast consecutive trues") {
    Given("a started actor")
    actorRef.start()

    When("the actor gets notified twice at the same time of wanted offers")
    offersWanted.onNext(true)
    offersWanted.onNext(true)

    Then("it calls reviveOffers once")
    Mockito.verify(driver, Mockito.timeout(1000)).reviveOffers()
    And("schedules the next revive for in 5 seconds")
    assert(actorRef.underlyingActor.scheduled == Vector(5.seconds))
    verifyNoMoreInteractions()
  }

  test("the third true has no effect") {
    Given("a started actor")
    actorRef.start()

    And("we already received two offers wanted notifications")
    offersWanted.onNext(true)
    offersWanted.onNext(true)
    Mockito.reset(driver)

    When("we get another offers wanted 3 seconds later")
    clock += 3.seconds
    offersWanted.onNext(true)

    Then("nothing happens because our next revive is already scheduled")
    verifyNoMoreInteractions()
  }

  test("Revive timer is cancelled if offers not wanted anymore") {
    Given("we received offersWanted = true two times and thus scheduled a timer")
    actorRef.start()
    offersWanted.onNext(true)
    offersWanted.onNext(true)

    Mockito.reset(driver)
    Mockito.reset(actorRef.underlyingActor.cancellable)

    When("we receive a false (= offers not wanted anymore) message")
    offersWanted.onNext(false)

    Then("we cancel the timer")
    Mockito.verify(actorRef.underlyingActor.cancellable, Mockito.timeout(1000)).cancel()
    verifyNoMoreInteractions()
  }

  test("Check revives if last offersWanted == true and more than 5.seconds ago") {
    Given("that we received various flipping offers wanted requests")
    actorRef.start()
    offersWanted.onNext(true)
    offersWanted.onNext(false)
    offersWanted.onNext(true)

    Mockito.reset(driver)
    Mockito.reset(actorRef.underlyingActor.cancellable)

    And("we wait for 5 seconds")
    clock += 5.seconds

    When("we receive a Check message")
    actorRef ! ReviveOffersActor.TimedCheck

    Then("we cancel our now unnecessary timer (which has send this message)")
    Mockito.verify(actorRef.underlyingActor.cancellable, Mockito.timeout(1000)).cancel()
    And("we revive the offers")
    Mockito.verify(driver, Mockito.timeout(1000)).reviveOffers()
    verifyNoMoreInteractions()
  }

  test("Check does not revives if last offersWanted == false and more than 5.seconds ago") {
    Given("that we received various flipping offers wanted requests")
    actorRef.start()
    offersWanted.onNext(true)
    offersWanted.onNext(true)
    offersWanted.onNext(false)

    Mockito.reset(driver)
    Mockito.reset(actorRef.underlyingActor.cancellable)

    And("we wait for 5 seconds")
    clock += 5.seconds

    When("we receive a Check message")
    actorRef ! ReviveOffersActor.TimedCheck

    Then("we do not do anything")
    verifyNoMoreInteractions()
  }

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] val conf = new ReviveOffersConfig {}
  conf.afterInit()
  private[this] var clock: ConstantClock = _
  private[this] var actorRef: TestActorRef[TestableActor] = _
  private[this] var offersWanted: Subject[Boolean] = _
  private[this] var driver: SchedulerDriver = _
  private[this] var driverHolder: MarathonSchedulerDriverHolder = _
  private[this] var mockScheduler: Scheduler = _

  before {
    actorSystem = ActorSystem()
    clock = ConstantClock()
    offersWanted = PublishSubject()
    driver = mock[SchedulerDriver]
    driverHolder = new MarathonSchedulerDriverHolder
    driverHolder.driver = Some(driver)
    mockScheduler = mock[Scheduler]

    actorRef = TestActorRef(new TestableActor)
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }

  private[this] def verifyNoMoreInteractions(): Unit = {
    def killActorAndWaitForDeath(): Terminated = {
      actorRef ! PoisonPill
      val deathWatch = TestProbe()
      deathWatch.watch(actorRef)
      deathWatch.expectMsgClass(classOf[Terminated])
    }

    Mockito.verifyNoMoreInteractions(actorRef.underlyingActor.cancellable)

    killActorAndWaitForDeath()

    Mockito.verifyNoMoreInteractions(driver)
    Mockito.verifyNoMoreInteractions(mockScheduler)
  }

  private class TestableActor extends ReviveOffersActor(
    clock, conf, actorSystem.eventStream, offersWanted, driverHolder
  ) {
    var scheduled = Vector.empty[FiniteDuration]
    var cancellable = mock[Cancellable]

    override protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
      scheduled :+= duration
      cancellable
    }
  }
}
