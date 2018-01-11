package mesosphere.mesos.client

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.IntegrationTest
import mesosphere.marathon.integration.setup.MesosClusterTest
import mesosphere.mesos.conf.MesosClientConf
import org.apache.mesos.v1.mesos.{Filters, FrameworkID, FrameworkInfo}
import org.apache.mesos.v1.scheduler.scheduler.Event
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually

@IntegrationTest
class MesosClientIntegrationTest extends AkkaUnitTest
  with MesosClusterTest
  with Eventually
  with Inside
  with StrictLogging {

  "Mesos client should successfully subscribe to mesos without framework Id" in withFixture() { f =>
    When("a framework subscribes")

    val event: Event = f.pull(1).head

    Then("a framework successfully subscribes without a framework Id")
    event.`type` shouldBe Some(Event.Type.SUBSCRIBED)


    And("connection context should be initialized")
    inside(f.client.contextPromise.future.futureValue) {
      case MesosConnectionContext(url, mesosStreamId, frameworkId) =>
        url.getHost shouldBe f.mesosHost
        url.getPort shouldBe f.mesosPort
        mesosStreamId shouldBe defined
        frameworkId shouldBe defined
    }
  }

  "Mesos client should successfully subscribe to mesos with framework Id" in {
    val frameworkID = FrameworkID(UUID.randomUUID().toString)

    When("a framework subscribes with a framework Id")
    withFixture(Some(frameworkID)) { f =>
      val event: Event = f.pull(1).head

      Then("first event received should be a subscribed event")
      event.`type` shouldBe Some(Event.Type.SUBSCRIBED)
      event.subscribed.get.frameworkId shouldBe frameworkID

      And("connection context should be initialized")
      inside(f.client.contextPromise.future.futureValue) {
        case MesosConnectionContext(url, mesosStreamId, frameworkId) =>
          url.getHost shouldBe f.mesosHost
          url.getPort shouldBe f.mesosPort
          mesosStreamId shouldBe defined
          frameworkId shouldBe Some(frameworkID)
      }
    }
  }

  "Mesos client should successfully receive heartbeat" in withFixture() { f =>
    When("a framework subscribes")
    val events: Seq[Event] = f.pull(2)

    Then("a heartbeat event should arrive")
    events.count(_.`type`.contains(Event.Type.HEARTBEAT)) should be > 0
  }

  "Mesos client should successfully receive offers" in withFixture() { f =>
    When("a framework subscribes")
    val events: Seq[Event] = f.pull(3)

    And("an offer should arrive")
    events.count(_.`type`.contains(Event.Type.OFFERS)) should be > 0
  }

  "Mesos client should successfully declines offers" in withFixture() { f =>
    When("a framework subscribes")
    var events = f.pull(3)

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
      events = f.pull(1)
      events.count(_.`type`.contains(Event.Type.OFFERS)) should be > 0
    }

    events.count(_.`type`.contains(Event.Type.OFFERS)) should be > 0
  }

  def withFixture(frameworkId: Option[FrameworkID] = None)(fn: Fixture => Unit): Unit = {
    val f = new Fixture(frameworkId)
    try fn(f) finally {
      f.switch.shutdown()
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
    val mesosHost = mesosUrl.getHost
    val mesosPort = mesosUrl.getPort

    val conf = new MesosClientConf(master = s"${mesosUrl.getHost}:${mesosUrl.getPort}")
    val client = new MesosClient(conf, frameworkInfo)

    val (switch: UniqueKillSwitch, queue: SinkQueueWithCancel[Event]) = client.mesosSource.toMat(Sink.queue())(Keep.both).run()

    /**
      * Method will pull n elements from the queue effectively awaiting n elements from mesos. Unlike take() e.g.
      * `f.client.mesosSource.take(1)` it will not cancel the upstream after the number of elements is received. A
      * TimeoutException is thrown should the element not be available withing `patienceConfig.timeout` milliseconds.
      *
      * @param n number of elements to pull
      * @return a sequence of elements
      */
    def pull(n: Int): Seq[Event] = {
      (1 to n).map(_ => queue.pull().futureValue.get)
    }
  }
}
