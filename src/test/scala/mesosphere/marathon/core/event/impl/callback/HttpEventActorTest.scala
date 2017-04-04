package mesosphere.marathon
package core.event.impl.callback

import akka.actor.{ Actor, Props }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.testkit.{ EventFilter, TestActorRef }
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.event.impl.callback.HttpEventActor.EventNotificationLimit
import mesosphere.marathon.core.event.impl.callback.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.core.event.{ EventConf, EventStreamAttached, EventSubscribers }
import mesosphere.marathon.integration.setup.WaitTestSupport.waitUntil

import scala.concurrent.duration._
import scala.concurrent.Future

class HttpEventActorTest extends AkkaUnitTest {
  case class Fixture(
      clock: ConstantClock = ConstantClock(),
      duration: FiniteDuration = 10.seconds,
      conf: EventConf = mock[EventConf],
      response: HttpResponse = HttpResponse()) {
    val metrics = new HttpEventActor.HttpEventActorMetrics()
    var responseAction: () => HttpResponse = () => response

    conf.slowConsumerDuration returns duration
    conf.eventRequestTimeout returns Timeout(duration)
  }

  override protected lazy val akkaConfig: Config = {
    ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")
  }

  class NoHttpEventActor(subscribers: Set[String], f: Fixture)
      extends HttpEventActor(f.conf, TestActorRef(Props(new ReturnSubscribersTestActor(subscribers))), f.metrics, f.clock) {
    var _requests = List.empty[HttpRequest]
    def requests = synchronized(_requests)

    override private[impl] def request(httpRequest: HttpRequest) = {
      _requests ::= httpRequest
      Future(f.responseAction())
    }
  }

  class ReturnSubscribersTestActor(subscribers: Set[String]) extends Actor {
    override def receive: Receive = {
      case GetSubscribers => sender ! EventSubscribers(subscribers)
    }
  }

  "HttpEventActor" should {
    "A message is broadcast to all subscribers with valid URI" in {
      val f = Fixture()
      Given("A HttpEventActor with 2 subscribers")
      val aut = TestActorRef(new NoHttpEventActor(Set("host1", "invalid uri", "host2"), f))

      When("An event is send to the actor")
      aut ! EventStreamAttached("remote")

      Then("The message is broadcast to both valid subscribers")
      waitUntil("Wait for 2 subscribers to get notified", 1.second) {
        aut.underlyingActor.requests.size == 2
      }
    }

    "If a message is send to non existing subscribers" in {
      val f = Fixture()
      f.responseAction = () => throw new RuntimeException("Cannot connect")
      Given("A HttpEventActor with 2 subscribers")
      val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2"), f))

      When("An event is send to the actor")
      aut ! EventStreamAttached("remote")

      Then("The callback listener is rate limited")
      waitUntil("Wait for rate limiting 2 subscribers", 1.second) {
        aut.underlyingActor.limiter("host1").backoffUntil.isDefined && aut.underlyingActor.limiter("host2").backoffUntil.isDefined
      }
    }

    "If a message is send to a slow subscriber" in {
      Given("A HttpEventActor with 1 subscriber")
      val f = Fixture()
      f.responseAction = () => { f.clock += 15.seconds; f.response }
      val aut = TestActorRef(new NoHttpEventActor(Set("host1"), f))

      When("An event is send to the actor")
      aut ! EventStreamAttached("remote")

      Then("The callback listener is rate limited")
      waitUntil("Wait for rate limiting 1 subscriber", 5.second) {
        aut.underlyingActor.limiter("host1").backoffUntil.isDefined
      }
    }

    "A rate limited subscriber will not be notified" in {
      val f = Fixture()
      Given("A HttpEventActor with 2 subscribers")
      val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2"), f))
      aut.underlyingActor.limiter += "host1" -> EventNotificationLimit(23, Some(100.seconds.fromNow))

      When("An event is send to the actor")
      Then("Only one subscriber is limited")
      EventFilter.info(start = "Will not send event event_stream_attached to unresponsive hosts: host1") intercept {
        aut ! EventStreamAttached("remote")
      }

      And("The message is send to the other subscriber")
      waitUntil("Wait for 1 subscribers to get notified", 1.second) {
        aut.underlyingActor.requests.size == 1
      }
    }

    "A rate limited subscriber with success will not have a future backoff" in {
      Given("A HttpEventActor with 2 subscribers, where one has a overdue backoff")
      val f = Fixture()
      val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2"), f))
      aut.underlyingActor.limiter += "host1" -> EventNotificationLimit(23, Some((-100).seconds.fromNow))
      aut.underlyingActor.limiter.map(_._2.backoffUntil).forall(_.map(_.isOverdue()).getOrElse(true))

      When("An event is send to the actor")
      aut ! EventStreamAttached("remote")

      Then("All subscriber are unlimited")
      waitUntil("All subscribers are unlimited", 1.second) {
        aut.underlyingActor.limiter.map(_._2.backoffUntil).forall(_.isEmpty)
      }
    }
  }
}
