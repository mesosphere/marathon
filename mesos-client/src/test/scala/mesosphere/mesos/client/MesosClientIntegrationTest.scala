package mesosphere.mesos.client

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.IntegrationTest
import mesosphere.marathon.integration.setup.MesosClusterTest
import mesosphere.mesos.conf.MesosConf
import org.apache.mesos.v1.mesos.{Filters, FrameworkID, FrameworkInfo}
import org.apache.mesos.v1.scheduler.scheduler.Event
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future

@IntegrationTest
class MesosClientIntegrationTest extends AkkaUnitTest
  with MesosClusterTest
  with Eventually
  with Inside
  with StrictLogging {

  "Mesos client should successfully subscribe to mesos without framework Id" in withFixture() { f =>
    When("a framework subscribes")

    val res: Future[Seq[Event]] = f.client.mesosSource.take(1).runWith(Sink.seq)

    Then("a framework successfully subscribes without a framework Id")
    inside(res.futureValue) {
      case events =>
        events.head.`type` shouldBe Some(Event.Type.SUBSCRIBED)
    }

    And("connection context should be initialized")
    inside(f.client.contextPromise.future.futureValue) {
      case ConnectionContext(host, port, mesosStreamId, frameworkId) =>
        host shouldBe f.conf.mesosMasterHost
        port shouldBe f.conf.mesosMasterPort
        mesosStreamId shouldBe defined
        frameworkId shouldBe defined
    }
  }

  "Mesos client should successfully subscribe to mesos with framework Id" in {
    val frameworkID = FrameworkID(UUID.randomUUID().toString)

    When("a framework subscribes with a framework Id")
    withFixture(Some(frameworkID)) { f =>
      val res: Future[Seq[Event]] = f.client.mesosSource.take(1).runWith(Sink.seq)

      Then("first event received should be a subscribed event")
      inside(res.futureValue) {
        case events =>
          events.head.`type` shouldBe Some(Event.Type.SUBSCRIBED)
          events.head.subscribed.get.frameworkId shouldBe frameworkID
      }

      And("connection context should be initialized")
      inside(f.client.contextPromise.future.futureValue) {
        case ConnectionContext(host, port, mesosStreamId, frameworkId) =>
          host shouldBe f.conf.mesosMasterHost
          port shouldBe f.conf.mesosMasterPort
          mesosStreamId shouldBe defined
          frameworkId shouldBe Some(frameworkID)
      }
    }
  }

  "Mesos client should successfully receive heartbeats" in withFixture() { f =>
    When("a framework subscribes")
    val res: Future[Seq[Event]] = f.client.mesosSource.take(2).runWith(Sink.seq)

    Then("a heartbeat event should arrive")
    inside(res.futureValue) {
      case events =>
        events.count(_.`type`.contains(Event.Type.HEARTBEAT)) should be > 0
    }
  }

  "Mesos client should successfully receive offers" in withFixture() { f =>
    When("a framework subscribes")
    val res: Future[Seq[Event]] = f.client.mesosSource.take(3).runWith(Sink.seq)

    And("an offer should arrive")
    inside(res.futureValue) {
      case events =>
        events.count(_.`type`.contains(Event.Type.OFFERS)) should be > 0
    }
  }

  "Mesos client should successfully declines offers" in withFixture() { f =>
    When("a framework subscribes")
    var events: Seq[Event] = f.client.mesosSource.take(3).runWith(Sink.seq).futureValue

    val offer = events.filter(_.`type`.contains(Event.Type.OFFERS)).head
    val offerId = offer.offers.get.offers.head.id

    And("an offer event is received")
    val decline = f.client.decline(
      offerIds = Seq(offerId),
      filters = Some(Filters(Some(0.0f)))
    )

    And("and an offer is declined")
    Source.single(decline).runWith(f.client.mesosSink)

    Then("eventually a new offer event arrives")
    eventually{
      events = f.client.mesosSource.take(1).runWith(Sink.seq).futureValue
      events.count(_.`type`.contains(Event.Type.OFFERS)) should be > 0
    }

    events.count(_.`type`.contains(Event.Type.OFFERS)) should be > 0
  }

  def withFixture(frameworkId: Option[FrameworkID] = None)(fn: Fixture => Unit): Unit = {
    val f = new Fixture(frameworkId)
    try fn(f) finally {
      f.client.killSwitch.shutdown()
    }
  }

  class Fixture(frameworkId: Option[FrameworkID] = None) {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val frameworkInfo = FrameworkInfo(
      user = "foo",
      name = "Example FOO Framework",
      id = frameworkId,
      roles = Seq("foo"),
      failoverTimeout = Some(0.0f),
      capabilities = Seq(FrameworkInfo.Capability(`type` = Some(FrameworkInfo.Capability.Type.MULTI_ROLE)))
    )

    val mesosUrl = new java.net.URI(mesos.url)

    val conf = new MesosConf(List("--master", s"${mesosUrl.getHost}:${mesosUrl.getPort}"))
    val client = new MesosClient(conf, frameworkInfo)
  }
}
