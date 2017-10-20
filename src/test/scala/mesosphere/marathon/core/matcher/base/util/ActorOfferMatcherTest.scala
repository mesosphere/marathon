package mesosphere.marathon
package core.matcher.base.util

import java.util.UUID

import akka.actor.ActorRef
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{ TestActor, TestProbe }
import com.google.inject.Provider
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.state.FaultDomain
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.OfferID
import org.apache.mesos.Protos.DomainInfo.{ FaultDomain => FaultDomainPB }
import org.apache.mesos.Protos.DomainInfo.FaultDomain.{ RegionInfo, ZoneInfo }
import org.apache.mesos.Protos.{ DomainInfo, FrameworkID, Offer, SlaveID, OfferID => MesosOfferIdProto }

class ActorOfferMatcherTest extends AkkaUnitTest {

  val homeFaultDomain = FaultDomain("homeRegion", "homeZone")
  val remoteFaultDomain = FaultDomain("remoteRegion", "remoteZone")

  "The ActorOfferMatcher" when {
    "receives remote region offer" should {
      "say it's interested in offer from non-home region" in new Fixture {
        val probe = TestProbe()
        val offerMatcher = new ActorOfferMatcher(probe.ref, None, () => Some(homeFaultDomain))

        offerMatcher.isInterestedIn(offerWithFaultRegion(remoteFaultDomain)) should be (true)
      }

      "say it's interested in offer from home region" in new Fixture {
        val probe = TestProbe()
        val offerMatcher = new ActorOfferMatcher(probe.ref, None, () => Some(homeFaultDomain))

        offerMatcher.isInterestedIn(offerWithFaultRegion(homeFaultDomain)) should be (true)
      }

      "say is interested when fault region not set" in new Fixture {
        val probe = TestProbe()
        val offerMatcher = new ActorOfferMatcher(probe.ref, None, () => Some(homeFaultDomain))

        offerMatcher.isInterestedIn(offerWithoutFaultRegion()) should be (true)
      }
    }

    "asking the actor" should {
      "find a match in time" in {
        val probe = TestProbe()
        probe.setAutoPilot(new TestActor.AutoPilot {
          override def run(sender: ActorRef, msg: Any): AutoPilot = {
            msg match {
              case ActorOfferMatcher.MatchOffer(offer, p) =>
                p.trySuccess(MatchedInstanceOps(OfferID("other"), Seq.empty, true))
                TestActor.NoAutoPilot
              case _ =>
                TestActor.NoAutoPilot
            }
          }
        })
        val offer = MarathonTestHelper.makeBasicOffer().build()

        val offerMatcher = new ActorOfferMatcher(probe.ref, None, () => None)
        val offerMatch: MatchedInstanceOps = offerMatcher.matchOffer(offer).futureValue

        offerMatch.offerId should not be (offer.getId)
        offerMatch.offerId.getValue should be("other")
      }
    }
  }

  class Fixture {
    def offerWithFaultRegion(faultDomain: FaultDomain) = {
      Offer.newBuilder()
        .setId(MesosOfferIdProto.newBuilder().setValue(UUID.randomUUID().toString))
        .setFrameworkId(FrameworkID.newBuilder().setValue("notanidframework"))
        .setSlaveId(SlaveID.newBuilder().setValue(s"slave1"))
        .setHostname("hostname")
        .setDomain(DomainInfo.newBuilder()
          .setFaultDomain(FaultDomainPB.newBuilder()
            .setRegion(RegionInfo.newBuilder().setName(faultDomain.region))
            .setZone(ZoneInfo.newBuilder().setName(faultDomain.zone)))
        ).build()
    }

    def offerWithoutFaultRegion() = {
      Offer.newBuilder()
        .setId(MesosOfferIdProto.newBuilder().setValue(UUID.randomUUID().toString))
        .setFrameworkId(FrameworkID.newBuilder().setValue("notanidframework"))
        .setSlaveId(SlaveID.newBuilder().setValue(s"slave1"))
        .setHostname("hostname")
        .build()
    }
  }
}
