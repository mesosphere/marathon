package mesosphere.marathon.core.election.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.event.LocalLeadershipEvent
import mesosphere.marathon.{ MarathonSpec, MarathonConf }
import mesosphere.marathon.core.election.{ ElectionCallback, ElectionCandidate, ElectionService }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.MarathonActorSupport
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.Mockito
import org.rogach.scallop.ScallopOption
import org.scalatest.{ GivenWhenThen, BeforeAndAfterAll, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

object ElectionServiceBaseTest {
  import Mockito.mock

  val MaxActorStartupTime = 5000L
  val OnElectedPrepareTimeout = 3 * 60 * 1000L

  def mockConfig: MarathonConf = {
    val config = mock(classOf[MarathonConf])

    Mockito.when(config.maxActorStartupTime).thenReturn(scallopOption(Some(MaxActorStartupTime)))
    Mockito.when(config.onElectedPrepareTimeout).thenReturn(scallopOption(Some(OnElectedPrepareTimeout)))
    Mockito.when(config.zkTimeoutDuration).thenReturn(1.second)

    config
  }

  def scallopOption[A](a: Option[A]): ScallopOption[A] = {
    new ScallopOption[A]("") {
      override def get = a
      override def apply() = a.get
    }
  }
}

class ElectionServiceBaseTest
    extends MarathonActorSupport
    with MarathonSpec
    with GivenWhenThen
    with BeforeAndAfterAll
    with Matchers {

  import ElectionServiceBaseTest._
  import ElectionServiceBase._

  private[this] var config: MarathonConf = _
  private[this] var httpConfig: HttpConf = _
  private[this] var electionService: ElectionService = _
  private[this] var events: EventStream = _
  private[this] var candidate: ElectionCandidate = _
  private[this] var metrics: Metrics = _
  private[this] var backoff: Backoff = _

  import scala.concurrent.ExecutionContext.Implicits.global

  before {
    config = mockConfig
    httpConfig = mock[HttpConf]
    electionService = mock[ElectionService]
    events = new EventStream()
    candidate = mock[ElectionCandidate]
    metrics = new Metrics(new MetricRegistry)
    backoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
  }

  test("state is Idle initially") {
    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = ???
      override def leaderHostPort: Option[String] = ???
    }

    awaitAssert(electionService.state should equal(Idle(candidate = None)))
  }

  test("state is eventually Offered after offerLeadership") {
    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPort: Option[String] = ???
    }

    Given("leadership is offered")
    electionService.offerLeadership(candidate)
    Then("state becomes Offered")
    awaitAssert(electionService.state should equal(Offered(candidate)))

    Given("leadership is offered again")
    electionService.offerLeadership(candidate)
    Then("state is still Offered")
    awaitAssert(electionService.state should equal(Offered(candidate)))
  }

  test("state is Offering after offerLeadership first") {
    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, new ExponentialBackoff(initialValue = 5.seconds)
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPort: Option[String] = ???
    }

    Given("leadership is offered")
    electionService.offerLeadership(candidate)
    Then("state becomes Offering")
    awaitAssert(electionService.state should equal(Offering(candidate)))
  }

  test("state is Abdicating after abdicateLeadership") {
    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPort: Option[String] = ???
    }

    Given("leadership is abdicated while not being leader")
    electionService.abdicateLeadership()
    Then("state stays Idle")
    awaitAssert(electionService.state should equal(Idle(None)))

    Given("leadership is offered and then abdicated")
    electionService.offerLeadership(candidate)
    awaitAssert(electionService.state should equal(Offered(candidate)))
    electionService.abdicateLeadership()
    Then("state is Abdicating with reoffer=false")
    awaitAssert(electionService.state should equal(Abdicating(candidate, reoffer = false)))

    Given("leadership is abdicated again")
    electionService.abdicateLeadership()
    Then("state is still Abdicating with reoffer=false")
    awaitAssert(electionService.state should equal(Abdicating(candidate, reoffer = false)))

    Given("leadership is abdicated again with reoffer=true")
    electionService.abdicateLeadership(reoffer = true)
    Then("state is still Abdicating with reoffer=true")
    awaitAssert(electionService.state should equal(Abdicating(candidate, reoffer = true)))

    Given("leadership is abdicated already with reoffer=true and the new reoffer is false")
    electionService.abdicateLeadership(reoffer = false)
    Then("state stays Abdicting with reoffer=true")
    awaitAssert(electionService.state should equal(Abdicating(candidate, reoffer = true)))
  }

  test("offerLeadership while abdicating") {
    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = ()
      override def leaderHostPort: Option[String] = ???
    }

    Given("leadership is offered, immediately abdicated and then offered again")
    electionService.offerLeadership(candidate)
    electionService.abdicateLeadership()
    awaitAssert(electionService.state should equal(Abdicating(candidate, reoffer = false)))
    Then("state is still Abdicating, but with reoffer=true")
    electionService.offerLeadership(candidate)
    awaitAssert(electionService.state should equal(Abdicating(candidate, reoffer = true)))
  }

  test("callbacks are called") {
    val cb = mock[ElectionCallback]
    Mockito.when(cb.onDefeated).thenReturn(Future(()))
    Mockito.when(cb.onElected).thenReturn(Future(()))

    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq(cb), backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = {
        startLeadership(_ => stopLeadership())
      }
      override def leaderHostPort: Option[String] = ???
    }

    Given("this instance is becoming leader")
    electionService.offerLeadership(candidate)
    awaitAssert(electionService.state.isInstanceOf[Leading])

    Then("the callbacks are called after the candidate")
    val order = Mockito.inOrder(cb, candidate)
    awaitAssert(order.verify(candidate).startLeadership())
    awaitAssert(order.verify(cb).onElected)

    Given("this instance is abdicating")
    electionService.abdicateLeadership(reoffer = false)
    awaitAssert(electionService.state.isInstanceOf[Idle])

    Then("the callbacks are called first, then the candidate")
    awaitAssert(order.verify(cb).onDefeated)
    awaitAssert(order.verify(candidate).stopLeadership())
  }

  test("events are sent") {
    events = mock[EventStream]

    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = {
        startLeadership(_ => stopLeadership())
      }
      override def leaderHostPort: Option[String] = ???
    }

    Given("this instance is becoming leader")
    electionService.offerLeadership(candidate)
    awaitAssert(electionService.state.isInstanceOf[Leading])

    Then("the candidate is called, then an event is published")
    val order = Mockito.inOrder(events, candidate)
    awaitAssert(order.verify(candidate).startLeadership())
    awaitAssert(order.verify(events).publish(LocalLeadershipEvent.ElectedAsLeader))

    Given("this instance is abdicating")
    electionService.abdicateLeadership(reoffer = false)
    awaitAssert(electionService.state.isInstanceOf[Idle])

    Then("the candidate is called, then an event is published")
    awaitAssert(order.verify(candidate).stopLeadership())
    awaitAssert(order.verify(events).publish(LocalLeadershipEvent.Standby))
  }

  test("leadership can be re-offered") {
    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = () // do not call startLeadership here
      override def leaderHostPort: Option[String] = ???
    }

    Given("this instance is becoming leader and then abdicting with reoffer=true")
    electionService.offerLeadership(candidate)
    awaitAssert(electionService.state.isInstanceOf[Leading])
    electionService.abdicateLeadership(reoffer = true)

    Then("then the instance is reoffering candidacy")
    awaitAssert(electionService.state.isInstanceOf[Offered])
  }

  test("leadership can be re-offered after an exception in candidate's startLeadership") {
    backoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
    val throwException = new AtomicBoolean(true)

    val electionService = new ElectionServiceBase(
      config, system, events, metrics, Seq.empty, backoff
    ) {
      override protected def offerLeadershipImpl(): Unit = {
        startLeadership(_ => stopLeadership())
      }
      override def leaderHostPort: Option[String] = ???
    }

    Mockito.when(candidate.startLeadership()).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        if (throwException.get()) {
          throw new Exception("candidate.startLeadership exception")
        }
      }
    })

    Given("this instance is offering leadership with reoffer=true and candidate.startLeadershop throws an exception")
    electionService.offerLeadership(candidate)

    Then("leadership is re-offered again and again, and the backoff timeout increases")
    awaitAssert(backoff.value() >= 0.09.seconds)

    Given("no exceptions are thrown anymore")
    throwException.set(false)

    Then("the instance is elected")
    awaitAssert(electionService.state.isInstanceOf[Leading])
  }
}