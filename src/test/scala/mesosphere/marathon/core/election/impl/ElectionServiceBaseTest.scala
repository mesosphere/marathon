package mesosphere.marathon
package core.election.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.duration._

class ElectionServiceBaseTest extends AkkaUnitTest with Eventually {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  import ElectionServiceBase._

  class Fixture {
    val httpConfig: HttpConf = mock[HttpConf]
    val electionService: ElectionService = mock[ElectionService]
    val events: EventStream = new EventStream(system)
    val candidate: ElectionCandidate = mock[ElectionCandidate]
    val backoff: Backoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
  }

  "ElectionServiceBase" should {
    "state is Idle initially" in {
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = ???
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      electionService.state should equal(Idle(candidate = None))
    }

    "state is eventually Offered after offerLeadership" in {
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = ()
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      Given("leadership is offered")
      electionService.offerLeadership(f.candidate)
      Then("state becomes Offered")
      eventually { electionService.state should equal(Offered(f.candidate)) }

      Given("leadership is offered again")
      electionService.offerLeadership(f.candidate)
      Then("state is still Offered")
      eventually { electionService.state should equal(Offered(f.candidate)) }
    }

    "state is Offering after offerLeadership first" in {
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events,
        new ExponentialBackoff(initialValue = 5.seconds), LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = ()
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      Given("leadership is offered")
      electionService.offerLeadership(f.candidate)
      Then("state becomes Offering")
      eventually { electionService.state should equal(Offering(f.candidate)) }
    }

    "state is Abdicating after abdicateLeadership" in {
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = ()
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      Given("leadership is abdicated while not being leader")
      electionService.abdicateLeadership()
      Then("state stays Idle")
      eventually { electionService.state should equal(Idle(None)) }

      Given("leadership is offered and then abdicated")
      electionService.offerLeadership(f.candidate)
      eventually { electionService.state should equal(Offered(f.candidate)) }
      electionService.abdicateLeadership()
      Then("state is Abdicating with reoffer=false")
      eventually { electionService.state should equal(Abdicating(f.candidate, reoffer = false)) }

      Given("leadership is abdicated again")
      electionService.abdicateLeadership()
      Then("state is still Abdicating with reoffer=false")
      eventually { electionService.state should equal(Abdicating(f.candidate, reoffer = false)) }

      Given("leadership is abdicated again with reoffer=true")
      electionService.abdicateLeadership(reoffer = true)
      Then("state is still Abdicating with reoffer=true")
      eventually { electionService.state should equal(Abdicating(f.candidate, reoffer = true)) }

      Given("leadership is abdicated already with reoffer=true and the new reoffer is false")
      electionService.abdicateLeadership(reoffer = false)
      Then("state stays Abdicting with reoffer=true")
      eventually { electionService.state should equal(Abdicating(f.candidate, reoffer = true)) }
    }

    "offerLeadership while abdicating" in {
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = ()
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      Given("leadership is offered, immediately abdicated and then offered again")
      electionService.offerLeadership(f.candidate)
      electionService.abdicateLeadership()
      eventually { electionService.state should equal(Abdicating(f.candidate, reoffer = false)) }
      Then("state is still Abdicating, but with reoffer=true")
      electionService.offerLeadership(f.candidate)
      eventually { electionService.state should equal(Abdicating(f.candidate, reoffer = true)) }
    }

    "events are sent" in {
      val f = new Fixture
      val events = mock[EventStream]

      val electionService = new ElectionServiceBase(
        system, events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = {
          startLeadership(_ => stopLeadership())
        }
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      Given("this instance is becoming leader")
      electionService.offerLeadership(f.candidate)
      eventually { electionService.state.isInstanceOf[Leading] }

      Then("the candidate is called, then an event is published")
      val order = Mockito.inOrder(events, f.candidate)
      eventually { order.verify(f.candidate).startLeadership() }
      eventually { order.verify(events).publish(LocalLeadershipEvent.ElectedAsLeader) }

      Given("this instance is abdicating")
      electionService.abdicateLeadership(reoffer = false)
      eventually { electionService.state.isInstanceOf[Idle] }

      Then("the candidate is called, then an event is published")
      eventually { order.verify(f.candidate).stopLeadership() }
      eventually { order.verify(events).publish(LocalLeadershipEvent.Standby) }
    }

    "leadership can be re-offered" in {
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = () // do not call startLeadership here
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
      }

      Given("this instance is becoming leader and then abdicating with reoffer=true")
      electionService.offerLeadership(f.candidate)
      eventually { electionService.state.isInstanceOf[Leading] }
      electionService.abdicateLeadership(reoffer = true)

      Then("then the instance is reoffering candidacy")
      eventually { electionService.state.isInstanceOf[Offered] }
    }

    "leadership can be re-offered after an exception in candidate's startLeadership" in {
      val f = new Fixture
      val backoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
      val throwException = new AtomicBoolean(true)

      val electionService = new ElectionServiceBase(
        system, f.events, backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = {
          startLeadership(_ => stopLeadership())
        }
        override def leaderHostPortImpl: Option[String] = ???
        override def localHostPort: String = ???
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
      eventually { backoff.value() >= 0.09.seconds }

      Given("no exceptions are thrown anymore")
      throwException.set(false)

      Then("the instance is elected")
      eventually { electionService.state.isInstanceOf[Leading] }
    }

    "leaderHostPort handles exceptions and returns None" in {
      Given("an ElactionServiceBase descendent throws an exception in leaderHostPortImpl")
      val f = new Fixture
      val electionService = new ElectionServiceBase(
        system, f.events, f.backoff, LifecycleState.Ignore
      ) {
        override protected def offerLeadershipImpl(): Unit = {
          startLeadership(_ => stopLeadership())
        }
        override def leaderHostPortImpl: Option[String] = {
          throw new Exception("leaderHostPortImpl exception")
        }
        override def localHostPort: String = ???
      }

      When("querying for leaderHostPort")
      val currentLeaderHostPort = electionService.leaderHostPort

      Then("it should return none")
      currentLeaderHostPort should be(None)
    }
  }
}
