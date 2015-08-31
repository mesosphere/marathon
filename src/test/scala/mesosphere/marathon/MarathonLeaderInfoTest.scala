package mesosphere.marathon

import java.util.concurrent.atomic.AtomicBoolean

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import com.twitter.common.zookeeper.Candidate
import mesosphere.marathon.metrics.Metrics
import org.mockito.Mockito
import org.scalatest.{ Matchers, GivenWhenThen }

class MarathonLeaderInfoTest extends MarathonSpec with GivenWhenThen with Matchers {
  class Fixture {
    lazy val candidate = mock[Candidate]
    lazy val maybeCandidate = Some(candidate)
    lazy val leader = new AtomicBoolean(false)
    lazy val eventStream = new EventStream()
    lazy val metrics = new MarathonLeaderInfoMetrics(new Metrics(new MetricRegistry))
    lazy val leaderInfo = new MarathonLeaderInfo(maybeCandidate, leader, eventStream, metrics)

    def verifyNoMoreInteractions(): Unit = {
      Mockito.verifyNoMoreInteractions(candidate)
    }
  }

  test("currentLeaderHostPort handles exceptions and returns None") {
    Given("a leaderInfo with a candidate which throws exceptions")
    val f = new Fixture
    Mockito
      .when(f.candidate.getLeaderData)
      .thenThrow(new RuntimeException("test failure!"))

    When("querying for currentLeaderHostPort")
    val currentLeaderHostPort = f.leaderInfo.currentLeaderHostPort()

    Then("it should return none")
    currentLeaderHostPort should be(None)
  }
}
