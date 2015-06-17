package mesosphere.marathon.core.flow.impl

import akka.actor.{ Cancellable, ActorSystem, Scheduler }
import akka.testkit.TestActorRef
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.flow.ReviveOffersConfig
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
    // after verifies that there are not further unwanted interactions
  }

  test("revive on first true") {
    Given("a started actor")
    actorRef.start()

    When("the actor gets notified of wanted offers")
    offersWanted.onNext(true)

    Then("reviveOffers is called")
    Mockito.verify(driver).reviveOffers()
  }

  test("only one revive for two fast consecutive trues") {
    Given("a started actor")
    actorRef.start()

    When("the actor gets notified twice at the same time of wanted offers")
    offersWanted.onNext(true)
    offersWanted.onNext(true)

    Then("it calls reviveOffers once")
    Mockito.verify(driver).reviveOffers()
    And("schedules the next revive for in 5 seconds")
    assert(actorRef.underlyingActor.scheduled == Vector(5.seconds))
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
    Mockito.verify(actorRef.underlyingActor.cancellable).cancel()
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
    actorRef ! ReviveOffersActor.Check

    Then("we cancel our now unnecessary timer (which has send this message)")
    Mockito.verify(actorRef.underlyingActor.cancellable).cancel()
    And("we revive the offers")
    Mockito.verify(driver).reviveOffers()
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
    actorRef ! ReviveOffersActor.Check

    Then("we do not do anything")
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
    Mockito.verifyNoMoreInteractions(actorRef.underlyingActor.cancellable)

    actorSystem.shutdown()
    actorSystem.awaitTermination()

    Mockito.verifyNoMoreInteractions(driver)
    Mockito.verifyNoMoreInteractions(mockScheduler)
  }

  private class TestableActor extends ReviveOffersActor(clock, conf, offersWanted, driverHolder) {
    var scheduled = Vector.empty[FiniteDuration]
    var cancellable = mock[Cancellable]

    override protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
      scheduled :+= duration
      cancellable
    }
  }
}
