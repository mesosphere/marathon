package mesosphere.marathon.core.election.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.{ MarathonConf, MarathonSpec, MarathonTestHelper }
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.MarathonActorSupport
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.Mockito
import org.scalatest.{ BeforeAndAfterAll, GivenWhenThen, Matchers }

import scala.concurrent.duration._

class ElectionServiceBaseTest
    extends MarathonActorSupport
    with MarathonSpec
    with GivenWhenThen
    with BeforeAndAfterAll
    with Matchers {

  import ElectionServiceBase._

  class Fixture {
    val config: MarathonConf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--max_actor_startup_time", "5000",
      "--on_elected_prepare_timeout", s"${3 * 60 * 1000L}",
      "--zk_timeout", "1000"
    )
    val httpConfig: HttpConf = mock[HttpConf]
    val electionService: ElectionService = mock[ElectionService]
    val events: EventStream = new EventStream(system)
    val candidate: ElectionCandidate = mock[ElectionCandidate]
    val metrics: Metrics = new Metrics(new MetricRegistry)
    val backoff: Backoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
    val shutdownHooks: ShutdownHooks = mock[ShutdownHooks]
  }

  test("state is Idle initially") {
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = ???
      override def leaderHostPortImpl: Option[String] = ???
    }

    electionService.state should equal(Idle(candidate = None))
  }

  test("state is eventually Offered after offerLeadership") {
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPortImpl: Option[String] = ???
    }

    Given("leadership is offered")
    electionService.offerLeadership(f.candidate)
    Then("state becomes Offered")
    awaitAssert(electionService.state should equal(Offered(f.candidate)))

    Given("leadership is offered again")
    electionService.offerLeadership(f.candidate)
    Then("state is still Offered")
    awaitAssert(electionService.state should equal(Offered(f.candidate)))
  }

  test("state is Offering after offerLeadership first") {
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics,
      new ExponentialBackoff(initialValue = 5.seconds), f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPortImpl: Option[String] = ???
    }

    Given("leadership is offered")
    electionService.offerLeadership(f.candidate)
    Then("state becomes Offering")
    awaitAssert(electionService.state should equal(Offering(f.candidate)))
  }

  test("state is Abdicating after abdicateLeadership") {
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPortImpl: Option[String] = ???
    }

    Given("leadership is abdicated while not being leader")
    electionService.abdicateLeadership()
    Then("state stays Idle")
    awaitAssert(electionService.state should equal(Idle(None)))

    Given("leadership is offered and then abdicated")
    electionService.offerLeadership(f.candidate)
    awaitAssert(electionService.state should equal(Offered(f.candidate)))
    electionService.abdicateLeadership()
    Then("state is Abdicating with reoffer=false")
    awaitAssert(electionService.state should equal(Abdicating(f.candidate, reoffer = false)))

    Given("leadership is abdicated again")
    electionService.abdicateLeadership()
    Then("state is still Abdicating with reoffer=false")
    awaitAssert(electionService.state should equal(Abdicating(f.candidate, reoffer = false)))

    Given("leadership is abdicated again with reoffer=true")
    electionService.abdicateLeadership(reoffer = true)
    Then("state is still Abdicating with reoffer=true")
    awaitAssert(electionService.state should equal(Abdicating(f.candidate, reoffer = true)))

    Given("leadership is abdicated already with reoffer=true and the new reoffer is false")
    electionService.abdicateLeadership(reoffer = false)
    Then("state stays Abdicting with reoffer=true")
    awaitAssert(electionService.state should equal(Abdicating(f.candidate, reoffer = true)))
  }

  test("offerLeadership while abdicating") {
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPortImpl: Option[String] = ???
    }

    Given("leadership is offered, immediately abdicated and then offered again")
    electionService.offerLeadership(f.candidate)
    electionService.abdicateLeadership()
    awaitAssert(electionService.state should equal(Abdicating(f.candidate, reoffer = false)))
    Then("state is still Abdicating, but with reoffer=true")
    electionService.offerLeadership(f.candidate)
    awaitAssert(electionService.state should equal(Abdicating(f.candidate, reoffer = true)))
  }

  test("events are sent") {
    val f = new Fixture
    val events = mock[EventStream]

    val electionService = new ElectionServiceBase(
      f.config, system, events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = {
        startLeadership(_ => stopLeadership())
      }
      override def leaderHostPortImpl: Option[String] = ???
    }

    Given("this instance is becoming leader")
    electionService.offerLeadership(f.candidate)
    awaitAssert(electionService.state.isInstanceOf[Leading])

    Then("the candidate is called, then an event is published")
    val order = Mockito.inOrder(events, f.candidate)
    awaitAssert(order.verify(f.candidate).startLeadership())
    awaitAssert(order.verify(events).publish(LocalLeadershipEvent.ElectedAsLeader))

    Given("this instance is abdicating")
    electionService.abdicateLeadership(reoffer = false)
    awaitAssert(electionService.state.isInstanceOf[Idle])

    Then("the candidate is called, then an event is published")
    awaitAssert(order.verify(f.candidate).stopLeadership())
    awaitAssert(order.verify(events).publish(LocalLeadershipEvent.Standby))
  }

  test("leadership can be re-offered") {
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = () // do not call startLeadership here
      override def leaderHostPortImpl: Option[String] = ???
    }

    Given("this instance is becoming leader and then abdicating with reoffer=true")
    electionService.offerLeadership(f.candidate)
    awaitAssert(electionService.state.isInstanceOf[Leading])
    electionService.abdicateLeadership(reoffer = true)

    Then("then the instance is reoffering candidacy")
    awaitAssert(electionService.state.isInstanceOf[Offered])
  }

  test("leadership can be re-offered after an exception in candidate's startLeadership") {
    val f = new Fixture
    val backoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
    val throwException = new AtomicBoolean(true)

    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = {
        startLeadership(_ => stopLeadership())
      }
      override def leaderHostPortImpl: Option[String] = ???
    }

    Mockito.when(f.candidate.startLeadership()).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        if (throwException.get()) {
          throw new Exception("candidate.startLeadership exception")
        }
      }
    })

    Given("this instance is offering leadership with reoffer=true and candidate.startLeadershop throws an exception")
    electionService.offerLeadership(f.candidate)

    Then("leadership is re-offered again and again, and the backoff timeout increases")
    awaitAssert(backoff.value() >= 0.09.seconds)

    Given("no exceptions are thrown anymore")
    throwException.set(false)

    Then("the instance is elected")
    awaitAssert(electionService.state.isInstanceOf[Leading])
  }

  test("leaderHostPort handles exceptions and returns None") {
    Given("an ElactionServiceBase descendent throws an exception in leaderHostPortImpl")
    val f = new Fixture
    val electionService = new ElectionServiceBase(
      f.config, system, f.events, f.metrics, f.backoff, f.shutdownHooks
    ) {
      override protected def offerLeadershipImpl(): Unit = {
        startLeadership(_ => stopLeadership())
      }
      override def leaderHostPortImpl: Option[String] = {
        throw new Exception("leaderHostPortImpl exception")
      }
    }

    When("querying for leaderHostPort")
    val currentLeaderHostPort = electionService.leaderHostPort

    Then("it should return none")
    currentLeaderHostPort should be(None)
  }
}