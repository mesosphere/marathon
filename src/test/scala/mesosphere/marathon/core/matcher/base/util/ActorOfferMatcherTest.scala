package mesosphere.marathon
package core.matcher.base.util

import java.util.UUID

import akka.actor.ActorRef
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{ TestActor, TestProbe }
import com.google.inject.Provider
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.OfferID
import org.apache.mesos.Protos.DomainInfo.FaultDomain
import org.apache.mesos.Protos.DomainInfo.FaultDomain.{ RegionInfo, ZoneInfo }
import org.apache.mesos.Protos.{ DomainInfo, FrameworkID, Offer, SlaveID, OfferID => MesosOfferIdProto }

class ActorOfferMatcherTest extends AkkaUnitTest {
  "The ActorOfferMatcher" when {
    "receives remote region offer" should {
      "say is not interested when from non-home region" in new Fixture {
        val homeRegionProvider = mock[HomeRegionProvider]
        homeRegionProvider.getHomeRegion returns Some("home")
        val probe = TestProbe()
        val offerMatcher = new ActorOfferMatcher(probe.ref, None, new Provider[HomeRegionProvider] {
          override def get() = homeRegionProvider
        })

        offerMatcher.isInterestedIn(offerWithFaultRegion("remote")) should be (false)
      }

      "say is interested when from home region" in new Fixture {
        val homeRegionProvider = mock[HomeRegionProvider]
        homeRegionProvider.getHomeRegion returns Some("home")
        val probe = TestProbe()
        val offerMatcher = new ActorOfferMatcher(probe.ref, None, new Provider[HomeRegionProvider] {
          override def get() = homeRegionProvider
        })

        offerMatcher.isInterestedIn(offerWithFaultRegion("home")) should be (true)
      }

      "say is interested when fault region not set" in new Fixture {
        val homeRegionProvider = mock[HomeRegionProvider]
        homeRegionProvider.getHomeRegion returns Some("home")
        val probe = TestProbe()
        val offerMatcher = new ActorOfferMatcher(probe.ref, None, new Provider[HomeRegionProvider] {
          override def get() = mock[MarathonScheduler]
        })

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

        val offerMatcher = new ActorOfferMatcher(probe.ref, None, new Provider[HomeRegionProvider] {
          override def get() = mock[HomeRegionProvider]
        })
        val offerMatch: MatchedInstanceOps = offerMatcher.matchOffer(offer).futureValue

        offerMatch.offerId should not be (offer.getId)
        offerMatch.offerId.getValue should be("other")
      }
    }
  }

  class Fixture {
    def offerWithFaultRegion(faultRegion: String) = {
      Offer.newBuilder()
        .setId(MesosOfferIdProto.newBuilder().setValue(UUID.randomUUID().toString))
        .setFrameworkId(FrameworkID.newBuilder().setValue("notanidframework"))
        .setSlaveId(SlaveID.newBuilder().setValue(s"slave1"))
        .setHostname("hostname")
        .setDomain(DomainInfo.newBuilder()
          .setFaultDomain(FaultDomain.newBuilder()
            .setRegion(RegionInfo.newBuilder().setName(faultRegion))
            .setZone(ZoneInfo.newBuilder().setName("zone")))
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
