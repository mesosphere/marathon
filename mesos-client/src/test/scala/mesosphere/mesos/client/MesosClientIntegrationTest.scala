package mesosphere.mesos.client

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer }
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.IntegrationTest
import mesosphere.marathon.integration.setup.MesosClusterTest
import mesosphere.mesos.conf.MesosClientConf
import org.apache.mesos.v1.mesos.{ Filters, FrameworkID, FrameworkInfo }
import org.apache.mesos.v1.scheduler.scheduler.Event
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually
import scala.annotation.tailrec

@IntegrationTest
class MesosClientIntegrationTest extends AkkaUnitTest
  with MesosClusterTest
  with Eventually
  with Inside
  with StrictLogging {

  "Mesos client should successfully subscribe to mesos without framework Id" in withFixture() { f =>
    When("a framework subscribes")
    Then("a framework successfully subscribes without a framework Id")
    f.client.frameworkId.value shouldNot be(empty)

    And("connection context should be initialized")
    f.client.connectionInfo.url.getHost shouldBe f.mesosHost
    f.client.connectionInfo.url.getPort shouldBe f.mesosPort
    f.client.connectionInfo.streamId.length() should be > 1
    f.client.frameworkId.value.length() should be > 1
  }

  "Mesos client should successfully subscribe to mesos with framework Id" in {
    val frameworkID = FrameworkID(UUID.randomUUID().toString)

    When("a framework subscribes with a framework Id")
    withFixture(Some(frameworkID)) { f =>
      Then("the client should identify as the specified frameworkId")
      f.client.frameworkId shouldBe frameworkID
    }
  }

  "Mesos client should successfully receive heartbeat" in withFixture() { f =>
    When("a framework subscribes")
    val heartbeat = f.pullUntil(_.`type`.contains(Event.Type.HEARTBEAT))

    Then("a heartbeat event should arrive")
    heartbeat shouldNot be(empty)
  }

  "Mesos client should successfully receive offers" in withFixture() { f =>
    When("a framework subscribes")
    val offer = f.pullUntil(_.`type`.contains(Event.Type.OFFERS))

    And("an offer should arrive")
    offer shouldNot be(empty)
  }

  "Mesos client should successfully declines offers" in withFixture() { f =>
    When("a framework subscribes")
    And("an offer event is received")
    val Some(offer) = f.pullUntil(_.`type`.contains(Event.Type.OFFERS))
    val offerId = offer.offers.get.offers.head.id

    And("and an offer is declined")
    val decline = f.client.decline(
      offerIds = Seq(offerId),
      filters = Some(Filters(Some(0.0f))))

    Source.single(decline).runWith(f.client.mesosSink)

    Then("eventually a new offer event arrives")
    val nextOffer = f.pullUntil(_.`type`.contains(Event.Type.OFFERS))
    nextOffer shouldNot be(empty)
  }

  def withFixture(frameworkId: Option[FrameworkID] = None)(fn: Fixture => Unit): Unit = {
    val f = new Fixture(frameworkId)
    try fn(f) finally {
      f.client.killSwitch.shutdown()
    }
  }

  class Fixture(existingFrameworkId: Option[FrameworkID] = None) {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val frameworkInfo = FrameworkInfo(
      user = "foo",
      name = "Example FOO Framework",
      id = existingFrameworkId,
      roles = Seq("foo"),
      failoverTimeout = Some(0.0f),
      capabilities = Seq(FrameworkInfo.Capability(`type` = Some(FrameworkInfo.Capability.Type.MULTI_ROLE))))

    val mesosUrl = new java.net.URI(mesos.url)
    val mesosHost = mesosUrl.getHost
    val mesosPort = mesosUrl.getPort

    val conf = new MesosClientConf(master = s"${mesosUrl.getHost}:${mesosUrl.getPort}")
    val client = MesosClient.connect(conf, frameworkInfo).run.futureValue

    val queue = client.mesosSource.
      runWith(Sink.queue())

    /**
      * Pull (and drop) elements from the queue until the predicate returns true. Does not cancel the upstream.
      *
      * Returns Some(element) when queue emits an event which matches the predicate
      * Returns None if queue ends (client closes) before the predicate matches
      * TimeoutException is thrown if no event is available within the `patienceConfig.timeout` duration.
      *
      * @param predicate Function to evaluate to see if event matches
      * @return matching event, if any
      */
    @tailrec final def pullUntil(predicate: Event => Boolean): Option[Event] =
      queue.pull().futureValue match {
        case e @ Some(event) if (predicate(event)) =>
          e
        case None =>
          None
        case _ =>
          pullUntil(predicate)
      }
  }
}
