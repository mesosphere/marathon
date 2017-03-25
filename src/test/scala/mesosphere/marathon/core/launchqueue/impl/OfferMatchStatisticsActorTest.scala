package mesosphere.marathon
package core.launchqueue.impl

import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.launcher.{ InstanceOp, OfferMatchResult }
import mesosphere.marathon.core.launchqueue.LaunchQueue.{ QueuedInstanceInfo, QueuedInstanceInfoWithStatistics }
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatisticsActor.{ LaunchFinished, SendStatistics }
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.NoOfferMatchReason
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.concurrent.Eventually

class OfferMatchStatisticsActorTest extends AkkaUnitTest with Eventually with ImplicitSender {

  "OfferMatchStatisticsActor" should {
    "Collect and aggregate OfferMatchResults" in {
      Given("Statistics actor with empty statistics")
      val f = new Fixture
      val actor = TestActorRef[OfferMatchStatisticsActor](OfferMatchStatisticsActor.props())

      When("The actor collects 5 events regarding 3 different apps")
      actor ! f.matchedA
      actor ! f.matchedB
      actor ! f.noMatchA
      actor ! f.noMatchB
      actor ! f.noMatchBSecond
      actor ! f.matchedC
      eventually {
        actor.underlyingActor.runSpecStatistics should have size 3
      }
      eventually {
        actor.underlyingActor.lastNoMatches should have size 2
      }

      Then("The actor aggregates the data correctly for app A")
      val statisticsA = actor.underlyingActor.runSpecStatistics(f.runSpecA.id)
      statisticsA.lastMatch should be(Some(f.matchedA))
      statisticsA.lastNoMatch should be(Some(f.noMatchA))

      Then("The actor aggregates the data correctly for app B")
      val statisticsB = actor.underlyingActor.runSpecStatistics(f.runSpecB.id)
      statisticsB.lastMatch should be(Some(f.matchedB))
      statisticsB.lastNoMatch should be(Some(f.noMatchBSecond))

      Then("The actor aggregates the data correctly for app C")
      val statisticsC = actor.underlyingActor.runSpecStatistics(f.runSpecC.id)
      statisticsC.lastMatch should be(Some(f.matchedC))
      statisticsC.lastNoMatch should be(empty)

      And("Stores the last NoMatches per runSpec/per agent for app A and B")
      val lastNoMatchesA = actor.underlyingActor.lastNoMatches(f.runSpecA.id)
      lastNoMatchesA should have size 1
      lastNoMatchesA.values.head should be(f.noMatchA)
      val lastNoMatchesB = actor.underlyingActor.lastNoMatches(f.runSpecB.id)
      lastNoMatchesB should have size 1
      lastNoMatchesB.values.head should be(f.noMatchBSecond)
    }

    "If the launch attempt is finished, the statistics will be reset" in {
      Given("Statistics actor with some statistics for app A and C")
      val f = new Fixture
      val actor = TestActorRef[OfferMatchStatisticsActor](OfferMatchStatisticsActor.props())
      actor ! f.matchedA
      actor ! f.noMatchA
      actor ! f.matchedC
      eventually {
        actor.underlyingActor.runSpecStatistics should have size 2
      }
      eventually {
        actor.underlyingActor.lastNoMatches should have size 1
      }
      actor.underlyingActor.runSpecStatistics.get(f.runSpecA.id) should be(defined)
      actor.underlyingActor.runSpecStatistics.get(f.runSpecC.id) should be(defined)

      When("The launch attempt for app A finishes")
      actor ! LaunchFinished(f.runSpecA.id)

      Then("The statistics for app A are removed")
      eventually {
        actor.underlyingActor.runSpecStatistics should have size 1
      }
      eventually {
        actor.underlyingActor.lastNoMatches should have size 0
      }
      actor.underlyingActor.runSpecStatistics.get(f.runSpecA.id) should be(empty)
      actor.underlyingActor.runSpecStatistics.get(f.runSpecC.id) should be(defined)
    }

    "Statistics can be queried" in {
      Given("Statistics actor with some statistics for app A and C")
      val f = new Fixture
      val actor = TestActorRef[OfferMatchStatisticsActor](OfferMatchStatisticsActor.props())
      actor ! f.matchedA
      actor ! f.noMatchA // linter:ignore IdenticalStatements
      actor ! f.noMatchA
      actor ! f.matchedC
      eventually {
        actor.underlyingActor.runSpecStatistics should have size 2
      }
      eventually {
        actor.underlyingActor.lastNoMatches should have size 1
      }
      actor.underlyingActor.runSpecStatistics.get(f.runSpecA.id) should be(defined)
      actor.underlyingActor.runSpecStatistics.get(f.runSpecC.id) should be(defined)

      When("The launch attempt for app A finishes")
      actor ! SendStatistics(self, Seq(
        QueuedInstanceInfo(f.runSpecA, inProgress = true, 1, 1, Timestamp.now(), Timestamp.now()),
        QueuedInstanceInfo(f.runSpecC, inProgress = true, 1, 1, Timestamp.now(), Timestamp.now())
      ))

      Then("The statistics for app A are removed")
      val infos = expectMsgAnyClassOf(classOf[Seq[QueuedInstanceInfoWithStatistics]])
      infos should have size 2
      val infoA = infos.find(_.runSpec == f.runSpecA).get
      infoA.lastMatch should be(Some(f.matchedA))
      infoA.lastNoMatch should be(Some(f.noMatchA))
      infoA.rejectSummaryLaunchAttempt should be(Map(NoOfferMatchReason.InsufficientCpus -> 2))
      infoA.rejectSummaryLastOffers should be(Map(NoOfferMatchReason.InsufficientCpus -> 1))
      infoA.lastNoMatches should have size 1
    }
  }
  class Fixture {
    val emptyStatistics = OfferMatchStatisticsActor.emptyStatistics
    val runSpecA = AppDefinition(PathId("/a"))
    val runSpecB = AppDefinition(PathId("/b"))
    val runSpecC = AppDefinition(PathId("/c"))
    def offerFrom(agent: String, cpus: Double = 4) = MarathonTestHelper.makeBasicOffer(cpus = cpus).setSlaveId(Mesos.SlaveID.newBuilder().setValue(agent)).build()
    val instanceOp = mock[InstanceOp]
    import mesosphere.mesos.NoOfferMatchReason._
    val reasonA = Seq(InsufficientCpus, InsufficientPorts, InsufficientMemory)
    val reasonB = Seq(InsufficientCpus, InsufficientPorts, InsufficientMemory, UnfulfilledConstraint)
    val noMatchA = OfferMatchResult.NoMatch(runSpecA, offerFrom("agent1"), reasonA, Timestamp.now())
    val matchedA = OfferMatchResult.Match(runSpecA, offerFrom("agent1"), instanceOp, Timestamp.now())
    val noMatchB = OfferMatchResult.NoMatch(runSpecB, offerFrom("agent2", cpus = 0.1), reasonB, Timestamp.now())
    val noMatchBSecond = OfferMatchResult.NoMatch(runSpecB, offerFrom("agent2", cpus = 0.2), reasonB, Timestamp.now())
    val matchedB = OfferMatchResult.Match(runSpecB, offerFrom("agent2"), instanceOp, Timestamp.now())
    val matchedC = OfferMatchResult.Match(runSpecC, offerFrom("agent3"), instanceOp, Timestamp.now())
  }
}
