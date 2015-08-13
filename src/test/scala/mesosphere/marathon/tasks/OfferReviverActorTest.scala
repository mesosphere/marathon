package mesosphere.marathon.tasks

import akka.actor.{ Cancellable, Props, Terminated, PoisonPill, ActorRef, ActorSystem }
import akka.event.Logging.Debug
import akka.event.{ Logging, EventStream }
import akka.testkit.TestProbe
import mesosphere.marathon.event.{ SchedulerRegisteredEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.scalatest.{ Matchers, GivenWhenThen, BeforeAndAfter }
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

class OfferReviverActorTest extends MarathonSpec with BeforeAndAfter with GivenWhenThen with Matchers {
  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var eventStream: EventStream = _
  private[this] var driverHolder: MarathonSchedulerDriverHolder = _
  private[this] var conf: OfferReviverConf = _
  private[this] var delegate: OfferReviverDelegate = _

  test("Revive offers on ReviveOffers message") {
    val actorRef = startActor()

    When("calling revive offers")
    delegate.reviveOffers()

    And("waiting for actor to process all messages and die")
    actorRef ! PoisonPill
    val probe = TestProbe()
    probe.watch(actorRef)
    probe.expectMsgAnyClassOf(classOf[Terminated])

    Then("we get a reviveOffer call")
    Mockito.verify(driverHolder.driver.get).reviveOffers()
  }

  test("Second revive offers results in scheduling") {
    @volatile
    var scheduleCheckCalled = 0

    val actorRef = startActor(Props(
      new OfferReviverActor(conf, eventStream, driverHolder) {
        override protected def scheduleCheck(duration: FiniteDuration): Cancellable = {
          scheduleCheckCalled += 1
          mock[Cancellable]
        }
      }
    ))

    When("calling revive offers")
    delegate.reviveOffers()
    delegate.reviveOffers()

    And("waiting for actor to process all messages and die")
    actorRef ! PoisonPill
    val probe = TestProbe()
    probe.watch(actorRef)
    probe.expectMsgAnyClassOf(classOf[Terminated])

    Then("we get ONE reviveOffer call")
    Mockito.verify(driverHolder.driver.get).reviveOffers()
    And("we get one scheduleCheck call")
    scheduleCheckCalled should be(1)
  }

  test("Revive offers on SchedulerReregisteredEvent message") {
    val actorRef = startActor()

    When("sending SchedulerReregisteredEvent")
    eventStream.publish(SchedulerReregisteredEvent("somemaster"))

    And("waiting for actor to process all messages and die")
    actorRef ! PoisonPill
    val probe = TestProbe()
    probe.watch(actorRef)
    probe.expectMsgAnyClassOf(classOf[Terminated])

    Then("we get a reviveOffer call")
    Mockito.verify(driverHolder.driver.get).reviveOffers()
  }

  test("Revive offers on SchedulerRegisteredEvent message") {
    val actorRef = startActor()

    When("sending SchedulerRegisteredEvent")
    eventStream.publish(SchedulerRegisteredEvent("frameworkid", "somemaster"))

    And("waiting for actor to process all messages and die")
    actorRef ! PoisonPill
    val probe = TestProbe()
    probe.watch(actorRef)
    probe.expectMsgAnyClassOf(classOf[Terminated])

    Then("we get a reviveOffer call")
    Mockito.verify(driverHolder.driver.get).reviveOffers()
  }

  before {
    actorSystem = ActorSystem()
    eventStream = new EventStream(debug = true)
    driverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(mock[SchedulerDriver])
    conf = new OfferReviverConf {}
    conf.afterInit()
  }

  private[this] def startActor(props: Props = OfferReviverActor.props(conf, eventStream, driverHolder)): ActorRef = {
    val eventStreamProbe = TestProbe()
    eventStream.subscribe(eventStreamProbe.ref, classOf[Debug])
    val actorRef = actorSystem.actorOf(
      props,
      OfferReviverActor.NAME
    )
    delegate = new OfferReviverDelegate(actorRef)

    // wait for actor to subscribe to event stream
    eventStreamProbe.expectMsgClass(classOf[Logging.Debug])
    val subscribeDebugMessage = "subscribing " + actorRef + " to channel " + classOf[SchedulerRegisteredEvent]
    eventStreamProbe.expectMsgPF() {
      case Logging.Debug(_, _, `subscribeDebugMessage`) =>
        log.info("subscribe has finished")
      // noop
    }

    actorRef
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }
}
