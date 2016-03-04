package mesosphere.marathon.event.http

import akka.actor.{ Actor, ActorSystem, Props }
import akka.testkit.{ EventFilter, TestActorRef }
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.base.{ ConstantClock, Clock }
import mesosphere.marathon.event.EventStreamAttached
import mesosphere.marathon.event.http.HttpEventActor.EventNotificationLimit
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.integration.setup.WaitTestSupport.waitUntil
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }
import spray.http.{ HttpRequest, HttpResponse, StatusCode }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

class HttpEventActorTest extends MarathonSpec with Mockito with GivenWhenThen with Matchers {

  test("A message is broadcast to all subscribers") {
    Given("A HttpEventActor with 2 subscribers")
    val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2")))

    When("An event is send to the actor")
    aut ! EventStreamAttached("remote")

    Then("The message is broadcast to both subscribers")
    waitUntil("Wait for 2 subscribers to get notified", 1.second) {
      aut.underlyingActor.requests.size == 2
    }
  }

  test("If a message is send to non existing subscribers") {
    Given("A HttpEventActor with 2 subscribers")
    val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2")))
    responseAction = () => throw new RuntimeException("Cannot connect")

    When("An event is send to the actor")
    aut ! EventStreamAttached("remote")

    Then("The callback listener is rate limited")
    waitUntil("Wait for rate limiting 2 subscribers", 1.second) {
      aut.underlyingActor.limiter("host1").backoffUntil.isDefined && aut.underlyingActor.limiter("host2").backoffUntil.isDefined
    }
  }

  test("If a message is send to a slow subscriber") {
    Given("A HttpEventActor with 1 subscriber")
    val aut = TestActorRef(new NoHttpEventActor(Set("host1")))
    responseAction = () => { clock += 15.seconds; response }

    When("An event is send to the actor")
    aut ! EventStreamAttached("remote")

    Then("The callback listener is rate limited")
    waitUntil("Wait for rate limiting 1 subscriber", 5.second) {
      aut.underlyingActor.limiter("host1").backoffUntil.isDefined
    }
  }

  test("A rate limited subscriber will not be notified") {
    Given("A HttpEventActor with 2 subscribers")
    val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2")))
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

  test("A rate limited subscriber with success will not have a future backoff") {
    Given("A HttpEventActor with 2 subscribers, where one has a overdue backoff")
    val aut = TestActorRef(new NoHttpEventActor(Set("host1", "host2")))
    aut.underlyingActor.limiter += "host1" -> EventNotificationLimit(23, Some((-100).seconds.fromNow))
    aut.underlyingActor.limiter.map(_._2.backoffUntil).forall(_.map(_.isOverdue()).getOrElse(true))

    When("An event is send to the actor")
    aut ! EventStreamAttached("remote")

    Then("All subscriber are unlimited")
    waitUntil("All subscribers are unlimited", 1.second) {
      aut.underlyingActor.limiter.map(_._2.backoffUntil).forall(_.isEmpty)
    }
  }

  var clock: ConstantClock = _
  var conf: HttpEventConfiguration = _
  var response: HttpResponse = _
  var statusCode: StatusCode = _
  var responseAction = () => response
  val metrics = new HttpEventActor.HttpEventActorMetrics(new Metrics(new MetricRegistry))

  implicit var system: ActorSystem = _

  before {
    system = ActorSystem("test-system",
      ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")
    )
    clock = ConstantClock()
    conf = mock[HttpEventConfiguration]
    conf.slowConsumerTimeout returns 10.seconds
    statusCode = mock[StatusCode]
    statusCode.isSuccess returns true
    response = mock[HttpResponse]
    response.status returns statusCode
    responseAction = () => response
  }

  after {
    system.shutdown()
    system.awaitTermination()
  }

  class NoHttpEventActor(subscribers: Set[String])
      extends HttpEventActor(conf, TestActorRef(Props(new ReturnSubscribersTestActor(subscribers))), metrics, clock) {
    var _requests = List.empty[HttpRequest]
    def requests = synchronized(_requests)
    override def pipeline(implicit ec: ExecutionContext): (HttpRequest) => Future[HttpResponse] = synchronized { request =>
      _requests ::= request
      Future(responseAction())
    }
  }

  class ReturnSubscribersTestActor(subscribers: Set[String]) extends Actor {
    override def receive: Receive = {
      case GetSubscribers => sender ! EventSubscribers(subscribers)
    }
  }
}
