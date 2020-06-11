package mesosphere.marathon
package core.launchqueue.impl

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.launcher.{InstanceOp, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatistics.LaunchFinished
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.NoOfferMatchReason
import org.apache.mesos.{Protos => Mesos}
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually

class OfferMatchStatisticsActorTest extends AkkaUnitTest with Eventually with Inside {
  import OfferMatchStatistics.{MatchResult, OfferMatchUpdate}

  override def materializerSettings =
    super.materializerSettings.withDispatcher(akka.testkit.CallingThreadDispatcher.Id)

  "OfferMatchStatisticsActor" should {
    "Collect and aggregate OfferMatchResults" in {
      Given("Statistics actor with empty statistics")
      val f = new Fixture
      When("The sinks receive 5 events regarding 3 different apps")
      val (runSpecStatisticsFold, noMatchStatisticsFold) = Source(
        List[OfferMatchUpdate](
          f.matchedA,
          f.matchedB,
          f.noMatchA,
          f.noMatchB,
          f.noMatchBSecond,
          f.matchedC
        )
      ).runWith(sinks)

      val (runSpecStatistics, noMatchStatistics) =
        (runSpecStatisticsFold.finalResult.futureValue, noMatchStatisticsFold.finalResult.futureValue)

      runSpecStatistics should have size 3
      noMatchStatistics should have size 2

      Then("The actor aggregates the data correctly for app A")
      val statisticsA = runSpecStatistics(f.runSpecA.id)
      statisticsA.lastMatch should be(Some(f.matchedA.matchResult))
      statisticsA.lastNoMatch should be(Some(f.noMatchA.matchResult))

      Then("The actor aggregates the data correctly for app B")
      val statisticsB = runSpecStatistics(f.runSpecB.id)
      statisticsB.lastMatch should be(Some(f.matchedB.matchResult))
      statisticsB.lastNoMatch should be(Some(f.noMatchBSecond.matchResult))

      Then("The actor aggregates the data correctly for app C")
      val statisticsC = runSpecStatistics(f.runSpecC.id)
      statisticsC.lastMatch should be(Some(f.matchedC.matchResult))
      statisticsC.lastNoMatch should be(empty)

      And("Stores the last NoMatches per runSpec/per agent for app A and B")
      val lastNoMatchesA = noMatchStatistics(f.runSpecA.id)
      lastNoMatchesA should have size 1
      lastNoMatchesA.values.head should be(f.noMatchA.matchResult)
      val lastNoMatchesB = noMatchStatistics(f.runSpecB.id)
      lastNoMatchesB should have size 1
      lastNoMatchesB.values.head should be(f.noMatchBSecond.matchResult)
    }

    "If the launch attempt is finished, the statistics will be reset" in {
      Given("Statistics actor with some statistics for app A and C")
      val f = new Fixture
      val (input, (runSpecStatisticsFold, noMatchStatisticsFold)) = Source
        .queue[OfferMatchUpdate](16, OverflowStrategy.fail)
        .toMat(sinks)(Keep.both)
        .run

      input.offer(f.matchedA)
      input.offer(f.noMatchA)
      input.offer(f.matchedC)
      runSpecStatisticsFold.readCurrentResult().futureValue should have size 2
      noMatchStatisticsFold.readCurrentResult().futureValue should have size 1
      inside(runSpecStatisticsFold.readCurrentResult().futureValue) {
        case result =>
          result.get(f.runSpecA.id) should be(defined)
          result.get(f.runSpecC.id) should be(defined)
      }

      When("The launch attempt for app A finishes")
      input.offer(LaunchFinished(f.runSpecA.id))

      Then("The statistics for app A are removed")
      runSpecStatisticsFold.readCurrentResult().futureValue should have size 1
      noMatchStatisticsFold.readCurrentResult().futureValue should have size 0

      inside(runSpecStatisticsFold.readCurrentResult().futureValue) {
        case result =>
          result.get(f.runSpecA.id) should be(empty)
          result.get(f.runSpecC.id) should be(defined)
      }
    }

    "Statistics can be queried" in {
      Given("Statistics actor with some statistics for app A and C")
      val f = new Fixture
      val (runSpecStatisticsFold, noMatchStatisticsFold) =
        Source(List[OfferMatchUpdate](f.matchedA, f.noMatchA, f.noMatchA, f.matchedC)).runWith(sinks)

      val (runSpecStatistics, noMatchStatistics) =
        (runSpecStatisticsFold.finalResult.futureValue, noMatchStatisticsFold.finalResult.futureValue)

      runSpecStatistics should have size 2
      noMatchStatistics should have size 1

      runSpecStatistics.get(f.runSpecA.id) should be(defined)
      runSpecStatistics.get(f.runSpecC.id) should be(defined)

      val infoA = runSpecStatistics(f.runSpecA.id)
      infoA.lastMatch should be(Some(f.matchedA.matchResult))
      infoA.lastNoMatch should be(Some(f.noMatchA.matchResult))
      infoA.rejectSummary should be(Map(NoOfferMatchReason.InsufficientCpus -> 2))
    }
  }

  def sinks =
    Flow[OfferMatchUpdate]
      .alsoToMat(OfferMatchStatistics.runSpecStatisticsSink)(Keep.right)
      .toMat(OfferMatchStatistics.noMatchStatisticsSink)(Keep.both)

  class Fixture {
    val runSpecA = AppDefinition(AbsolutePathId("/a"), role = "*")
    val runSpecB = AppDefinition(AbsolutePathId("/b"), role = "*")
    val runSpecC = AppDefinition(AbsolutePathId("/c"), role = "*")
    def offerFrom(agent: String, cpus: Double = 4) =
      MarathonTestHelper.makeBasicOffer(cpus = cpus).setSlaveId(Mesos.SlaveID.newBuilder().setValue(agent)).build()
    val instanceOp = mock[InstanceOp]
    import mesosphere.mesos.NoOfferMatchReason._
    val reasonA = Seq(InsufficientCpus, InsufficientPorts, InsufficientMemory)
    val reasonB = Seq(InsufficientCpus, InsufficientPorts, InsufficientMemory, UnfulfilledConstraint)
    val noMatchA = MatchResult(OfferMatchResult.NoMatch(runSpecA, offerFrom("agent1"), reasonA, Timestamp.now()))
    val matchedA = MatchResult(OfferMatchResult.Match(runSpecA, offerFrom("agent1"), instanceOp, Timestamp.now()))
    val noMatchB = MatchResult(OfferMatchResult.NoMatch(runSpecB, offerFrom("agent2", cpus = 0.1), reasonB, Timestamp.now()))
    val noMatchBSecond = MatchResult(OfferMatchResult.NoMatch(runSpecB, offerFrom("agent2", cpus = 0.2), reasonB, Timestamp.now()))
    val matchedB = MatchResult(OfferMatchResult.Match(runSpecB, offerFrom("agent2"), instanceOp, Timestamp.now()))
    val matchedC = MatchResult(OfferMatchResult.Match(runSpecC, offerFrom("agent3"), instanceOp, Timestamp.now()))
  }
}
