package mesosphere.marathon
package core.launchqueue

import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdated}
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatistics.MatchResult
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatistics
import mesosphere.marathon.state.{PathId, RunSpec, Timestamp}
import mesosphere.marathon.stream.LiveFold
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{Protos => Mesos}

import scala.concurrent.Future
import scala.concurrent.duration._

class LaunchStatsTest extends AkkaUnitTest {
  import LaunchStats.LaunchingInstance

  class Fixture {
    val runSpecA = MarathonTestHelper.makeBasicApp(id = PathId("/a"))
    def offerFrom(agent: String, cpus: Double = 4) = MarathonTestHelper.makeBasicOffer(cpus = cpus)
      .setSlaveId(Mesos.SlaveID.newBuilder().setValue(agent)).build()
    val instanceOp = mock[InstanceOp]
    import mesosphere.mesos.NoOfferMatchReason._
    val reasonA = Seq(InsufficientCpus, InsufficientPorts, InsufficientMemory)
    val reasonB = Seq(InsufficientCpus, InsufficientPorts, InsufficientMemory, UnfulfilledConstraint)
    val noMatchA = OfferMatchResult.NoMatch(runSpecA, offerFrom("agent1"), reasonA, Timestamp.now())
    val matchedA = MatchResult(OfferMatchResult.Match(runSpecA, offerFrom("agent1"), instanceOp, Timestamp.now()))

    // instance related stuff
    val instance = TestInstanceBuilder.newBuilder(runSpecId = runSpecA.id).addTaskProvisioned().getInstance()
    val instanceId = instance.instanceId
    val scheduled = instance.copy(state = instance.state.copy(condition = Condition.Scheduled))
    val provisioned = instance.copy(state = instance.state.copy(condition = Condition.Provisioned))
    val running = instance.copy(state = instance.state.copy(condition = Condition.Running))
    val ts1 = Timestamp.zero
    val ts2 = ts1 + 1.minute
    val ts3 = ts1 + 2.minutes

    val runSpecs: Map[PathId, RunSpec] = Seq(runSpecA).map { r => r.id -> r }.toMap
  }

  "launchingInstancesFold" should {
    "keeps track of the first time the instance was reported as scheduled or provisioned" in new Fixture {
      val fold = Source(List[(Timestamp, InstanceChange)](
        ts1 -> InstanceUpdated(scheduled, None, Nil),
        ts2 -> InstanceUpdated(provisioned, None, Nil)))
        .runWith(LaunchStats.launchingInstancesFold)

      val result = fold.finalResult.futureValue
      result(instance.instanceId).since shouldBe ts1
      result(instance.instanceId).instance shouldBe provisioned
    }

    "clears instances from the map when they are neither scheduled or provisioned" in new Fixture {
      val fold = Source(List[(Timestamp, InstanceChange)](
        ts1 -> InstanceUpdated(scheduled, None, Nil),
        ts2 -> InstanceUpdated(provisioned, None, Nil),
        ts3 -> InstanceUpdated(running, None, Nil)))
        .runWith(LaunchStats.launchingInstancesFold)

      val result = fold.finalResult.futureValue
      result.shouldBe(empty)
    }
  }

  case class FoldFixture[T](result: T) extends LiveFold.Folder[T] {
    override def readCurrentResult(): Future[T] = Future.successful(result)
    override val finalResult: Future[T] = Future.never
  }

  "offer stats still shows a delay even though there are no offer stats" in new Fixture {
    val stats = new LaunchStats(
      getRunSpec = runSpecs.get(_),
      delays = FoldFixture(Map(runSpecA.configRef -> ts2)),
      launchingInstances = FoldFixture(Map(
        instanceId -> LaunchingInstance(ts1, scheduled),
      )),
      runSpecStatistics = FoldFixture(Map.empty),
      noMatchStatistics = FoldFixture(Map.empty))

    val Seq(stat) = stats.getStatistics().futureValue
    stat.backOffUntil shouldBe Some(ts2)
  }

  "shows runSpecStatistics" in new Fixture {
    val stats = new LaunchStats(
      getRunSpec = runSpecs.get(_),
      delays = FoldFixture(Map.empty),
      launchingInstances = FoldFixture(Map(
        instanceId -> LaunchingInstance(ts1, scheduled),
      )),
      runSpecStatistics = FoldFixture(Map(
        runSpecA.id -> OfferMatchStatistics.RunSpecOfferStatistics.apply(
          rejectSummary = Map(),
          processedOfferCount = 4,
          unusedOfferCount = 8,
          lastMatch = None,
          lastNoMatch = Some(noMatchA))
      )),
      noMatchStatistics = FoldFixture(Map.empty))

    val Seq(stat) = stats.getStatistics().futureValue
    stat.inProgress shouldBe true
    stat.lastMatch shouldBe None
    stat.lastNoMatch shouldBe Some(noMatchA)
    stat.unusedOffersCount shouldBe 8
  }
}
