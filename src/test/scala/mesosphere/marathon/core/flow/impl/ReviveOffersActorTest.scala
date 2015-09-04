package mesosphere.marathon.core.flow.impl

import akka.actor._
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.flow.impl.ReviveOffersActor.TimedCheck
import mesosphere.marathon.event.{ SchedulerRegisteredEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.scalatest.{ Matchers, GivenWhenThen }
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.concurrent.duration._

class ReviveOffersActorTest extends MarathonSpec with GivenWhenThen with Matchers {
  test("do not do anything") {
    val f = new Fixture()
    When("the actor starts")
    f.actorRef.start()

    Then("there are no surprising interactions")
    f.verifyNoMoreInteractions()
  }

  test("revive on first OffersWanted(true)") {
    val f = new Fixture()
    Given("a started actor")
    f.actorRef.start()

    When("the actor gets notified of wanted offers")
    f.offersWanted.onNext(true)

    Then("reviveOffers is called")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
    f.verifyNoMoreInteractions()
  }

  test(s"revive if offers wanted and we receive explicit reviveOffers") {
    val f = new Fixture()
    Given("a started actor that wants offers")
    f.actorRef.start()
    f.offersWanted.onNext(true)
    Mockito.reset(f.driver)
    f.clock += 10.seconds

    When("we explicitly reviveOffers")
    val offerReviver = new OfferReviverDelegate(f.actorRef)
    offerReviver.reviveOffers()

    Then("reviveOffers is called directly")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
    f.verifyNoMoreInteractions()
  }

  for (
    reviveEvent <- Seq(
      SchedulerReregisteredEvent("somemaster"),
      SchedulerRegisteredEvent("frameworkid", "somemaster")
    )
  ) {
    test(s"revive if offers wanted and we receive $reviveEvent") {
      val f = new Fixture()
      Given("a started actor that wants offers")
      f.actorRef.start()
      f.offersWanted.onNext(true)
      Mockito.reset(f.driver)
      f.clock += 10.seconds

      When(s"the actor receives an $reviveEvent message")
      f.actorRef ! reviveEvent

      Then("reviveOffers is called directly")
      Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
      f.verifyNoMoreInteractions()
    }
  }

  test(s"NO revive if revivesWanted == 0 and we receive TimedCheck") {
    val f = new Fixture()
    Given("a started actor that wants offers")
    f.actorRef.start()
    f.offersWanted.onNext(true) // immediate revive, nothing scheduled
    Mockito.reset(f.driver)
    f.clock += 10.seconds

    When(s"the actor receives an TimedCheck message")
    f.actorRef ! TimedCheck

    Then("reviveOffers is NOT called")
    f.verifyNoMoreInteractions()
  }

  test(s"revive if revivesWanted > 0 and we receive TimedCheck") {
    val f = new Fixture()
    Given("a started actor that wants offers")
    f.actorRef.start()
    f.offersWanted.onNext(true) // immediate revive
    f.offersWanted.onNext(true) // one revive scheduled
    Mockito.reset(f.driver)
    f.clock += 10.seconds

    When(s"the actor receives an TimedCheck message")
    f.actorRef ! TimedCheck

    Then("reviveOffers is called directly")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()

    And("any scheduled timers are canceled")
    Mockito.verify(f.actorRef.underlyingActor.cancellable, Mockito.timeout(1000)).cancel()

    f.verifyNoMoreInteractions()
  }

  for (
    reviveEvent <- Seq(
      SchedulerReregisteredEvent("somemaster"),
      SchedulerRegisteredEvent("frameworkid", "somemaster"),
      ReviveOffersActor.TimedCheck
    )
  ) {
    test(s"DO NOT revive if offers NOT wanted and we receive $reviveEvent") {
      val f = new Fixture()
      Given("a started actor that wants offers")
      f.actorRef.start()
      f.offersWanted.onNext(false)
      Mockito.reset(f.driver)
      f.clock += 10.seconds

      When(s"the actor receives an $reviveEvent message")
      f.actorRef ! reviveEvent

      Then("reviveOffers is NOT called directly")
      f.verifyNoMoreInteractions()
    }
  }

  test("only one revive for two fast consecutive trues") {
    val f = new Fixture()
    Given("a started actor")
    f.actorRef.start()

    When("the actor gets notified twice at the same time of wanted offers")
    f.offersWanted.onNext(true)
    f.offersWanted.onNext(true)

    Then("it calls reviveOffers once")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
    And("schedules the next revive for in 5 seconds")
    assert(f.actorRef.underlyingActor.scheduled == Vector(5.seconds))
    f.verifyNoMoreInteractions()
  }

  test("the third true has no effect") {
    val f = new Fixture()
    Given("a started actor")
    f.actorRef.start()

    And("we already received two offers wanted notifications")
    f.offersWanted.onNext(true)
    f.offersWanted.onNext(true)
    Mockito.reset(f.driver)

    When("we get another offers wanted 3 seconds later")
    f.clock += 3.seconds
    f.offersWanted.onNext(true)

    Then("nothing happens because our next revive is already scheduled")
    f.verifyNoMoreInteractions()
  }

  test("Revive timer is cancelled if offers not wanted anymore") {
    val f = new Fixture()
    Given("we received offersWanted = true two times and thus scheduled a timer")
    f.actorRef.start()
    f.offersWanted.onNext(true)
    f.offersWanted.onNext(true)

    Mockito.reset(f.driver)
    Mockito.reset(f.actorRef.underlyingActor.cancellable)

    When("we receive a false (= offers not wanted anymore) message")
    f.offersWanted.onNext(false)

    Then("we cancel the timer")
    Mockito.verify(f.actorRef.underlyingActor.cancellable, Mockito.timeout(1000)).cancel()
    f.verifyNoMoreInteractions()
  }

  test("Check revives if last offersWanted == true and more than 5.seconds ago") {
    val f = new Fixture()
    Given("that we received various flipping offers wanted requests")
    f.actorRef.start()
    f.offersWanted.onNext(true)
    f.offersWanted.onNext(false)
    f.offersWanted.onNext(true)

    Mockito.reset(f.driver)
    Mockito.reset(f.actorRef.underlyingActor.cancellable)

    And("we wait for 5 seconds")
    f.clock += 5.seconds

    When("we receive a Check message")
    f.actorRef ! ReviveOffersActor.TimedCheck

    Then("we cancel our now unnecessary timer (which has send this message)")
    Mockito.verify(f.actorRef.underlyingActor.cancellable, Mockito.timeout(1000)).cancel()
    And("we revive the offers")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
    f.verifyNoMoreInteractions()
  }

  test("Check does not revives if last offersWanted == false and more than 5.seconds ago") {
    val f = new Fixture()
    Given("that we received various flipping offers wanted requests")
    f.actorRef.start()
    f.offersWanted.onNext(true)
    f.offersWanted.onNext(true)
    f.offersWanted.onNext(false)

    Mockito.reset(f.driver)
    Mockito.reset(f.actorRef.underlyingActor.cancellable)

    And("we wait for 5 seconds")
    f.clock += 5.seconds

    When("we receive a Check message")
    f.actorRef ! ReviveOffersActor.TimedCheck

    Then("we do not do anything")
    f.verifyNoMoreInteractions()
  }

  test("revive on repeatedly while OffersWanted(true)") {
    val f = new Fixture(repetitions = 5)
    Given("a started actor")
    f.actorRef.start()

    When("the actor gets notified of wanted offers")
    f.offersWanted.onNext(true)

    Then("reviveOffers is called and we schedule more revives")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
    And("we scheduled the first of many revives")
    f.actorRef.underlyingActor.scheduled should have size (1)
    f.actorRef.underlyingActor.revivesNeeded should be (f.repetitions - 1)

    Mockito.reset(f.driver)

    for (i <- 2L to f.repetitions.toLong - 1) {
      When("the min_revive_offers_interval has passed and we receive a TimedCheck")
      f.clock += f.conf.minReviveOffersInterval().millis
      f.actorRef ! ReviveOffersActor.TimedCheck

      Then("reviveOffers is called")
      Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
      Mockito.verifyNoMoreInteractions(f.driver)
      Mockito.reset(f.driver)

      And("current timer gets canceled")
      Mockito.verify(f.actorRef.underlyingActor.cancellable).cancel()
      Mockito.verifyNoMoreInteractions(f.actorRef.underlyingActor.cancellable)
      Mockito.reset(f.actorRef.underlyingActor.cancellable)

      And("we have scheduled the next revive")
      f.actorRef.underlyingActor.scheduled should have size i
      f.actorRef.underlyingActor.revivesNeeded should be (f.repetitions - i)
    }

    When("the min_revive_offers_interval has passed and we receive our last TimedCheck")
    f.clock += f.conf.minReviveOffersInterval().millis
    f.actorRef ! ReviveOffersActor.TimedCheck

    Then("reviveOffers is called for the last time")
    Mockito.verify(f.driver, Mockito.timeout(1000)).reviveOffers()
    Mockito.verifyNoMoreInteractions(f.driver)
    Mockito.reset(f.driver)

    And("current timer gets canceled")
    Mockito.verify(f.actorRef.underlyingActor.cancellable).cancel()
    Mockito.verifyNoMoreInteractions(f.actorRef.underlyingActor.cancellable)
    Mockito.reset(f.actorRef.underlyingActor.cancellable)

    And("we have NOT scheduled the next revive")
    f.actorRef.underlyingActor.scheduled should have size (f.repetitions.toLong - 1)
    f.actorRef.underlyingActor.revivesNeeded should be (0)

    f.verifyNoMoreInteractions()
  }

  private[this] implicit var actorSystem: ActorSystem = _

  before {
    actorSystem = ActorSystem()
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }

  class Fixture(val repetitions: Int = 1) {
    lazy val conf: ReviveOffersConfig = {
      val conf = new ReviveOffersConfig {
        override lazy val reviveOffersRepetitions = opt[Int]("revive_offers_repetitions",
          descr = "Repeat every reviveOffer request this many times, delayed by the --min_revive_offers_interval.",
          default = Some(repetitions))
      }
      conf.afterInit()
      conf
    }
    lazy val clock: ConstantClock = ConstantClock()
    lazy val offersWanted: Subject[Boolean] = PublishSubject()
    lazy val driver: SchedulerDriver = mock[SchedulerDriver]
    lazy val driverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(driver)
      holder
    }
    lazy val mockScheduler: Scheduler = mock[Scheduler]
    lazy val actorRef = TestActorRef(new TestableActor)

    def verifyNoMoreInteractions(): Unit = {
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

    class TestableActor extends ReviveOffersActor(
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
}
