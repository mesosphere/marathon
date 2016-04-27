package mesosphere.marathon

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.metrics.Metrics
import org.mockito.Mockito
import org.scalatest.{ Matchers, GivenWhenThen }

class MarathonLeaderInfoTest extends MarathonSpec with GivenWhenThen with Matchers {
  class Fixture {
    lazy val electionService = mock[ElectionService]
    lazy val eventStream = new EventStream()
    lazy val metrics = new MarathonLeaderInfoMetrics(new Metrics(new MetricRegistry))
    lazy val leaderInfo = new MarathonLeaderInfo(electionService, eventStream, metrics)

    def verifyNoMoreInteractions(): Unit = {
      Mockito.verifyNoMoreInteractions(electionService)
    }
  }

  test("currentLeaderHostPort handles exceptions and returns None") {
    Given("a leaderInfo with an ElectionService which throws exceptions")
    val f = new Fixture
    Mockito
      .when(f.electionService.leaderHostPort)
      .thenThrow(new RuntimeException("test failure!"))

    When("querying for currentLeaderHostPort")
    val currentLeaderHostPort = f.leaderInfo.currentLeaderHostPort()

    Then("it should return none")
    currentLeaderHostPort should be(None)
  }
}
