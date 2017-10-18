package mesosphere.marathon
package api.akkahttp.v2

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.http.scaladsl.model.RemoteAddress
import akka.stream.{ ActorMaterializer, ThrottleMode }
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.config.{ Config, ConfigFactory }
import mesosphere.UnitTest
import mesosphere.marathon.api.akkahttp.v2.EventsController.EventStreamSourceGraph
import mesosphere.marathon.core.event.{ ApiPostEvent, MarathonEvent }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Second, Seconds, Span }

import scala.concurrent.Await
import scala.concurrent.duration._

class EventsControllerTest extends UnitTest {
  import EventsControllerTest._
  implicit val system = ActorSystem("EventsControllerTest", ConfigFactory.load(testConf))
  implicit val materializer = ActorMaterializer()

  "EventStreamSourceGraph" should {
    "be a valid source" in {
      val fakeEventBus: EventStream = new EventStream(system)
      val sourceUnderTest = Source
        .fromGraph[MarathonEvent, NotUsed](
          new EventStreamSourceGraph(fakeEventBus, 5, fakeClientIp)
        )
      val fut = sourceUnderTest.take(3).runWith(Sink.seq)
      Thread.sleep(200) // avoid race with materialization of the stream
      fakeEventBus.publish(fakeApiPostEvent)
      Thread.sleep(200)
      fakeEventBus.publish(fakeApiPostEvent)
      val result = Await.result(fut, 5 seconds)
      println(result)
      assert(result.tail.head == fakeApiPostEvent)
    }

    "fail for queue overload" in {
      val fakeEventBus: EventStream = new EventStream(system)
      val sourceUnderTest = Source
        .fromGraph[MarathonEvent, NotUsed](
          new EventStreamSourceGraph(fakeEventBus, 1, fakeClientIp)
        )
      val fut = sourceUnderTest.take(3).throttle(1, 1 second, 1, ThrottleMode.shaping).runWith(Sink.seq)

      Thread.sleep(200) // avoid materialization race
      var i: Int = 0
      while (i < 20) {
        fakeEventBus.publish(fakeApiPostEvent)
        i += 1
      }
      fakeEventBus.publish(fakeApiPostEvent)

      implicit val patienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(150, Millis)))
      // assertion
      ScalaFutures.whenReady(fut.failed) { e =>
        e shouldBe a[akka.stream.BufferOverflowException]
      }
    }
  }

}

object EventsControllerTest {
  val testConf: Config = ConfigFactory.parseString("""
  akka {
    loglevel = "WARNING"
    stdout-loglevel = "WARNING"
    actor {
      default-dispatcher {
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = 8
          parallelism-factor = 2.0
          parallelism-max = 8
        }
      }
    }
  }""")

  val fakeAddr: Array[Byte] = List[Int](192, 168, 0, 1).map { _.toByte }.toArray
  val fakeClientIp: RemoteAddress = RemoteAddress(fakeAddr)
  val fakeAppDefinition = new AppDefinition(PathId(List("fakeApp")))
  val fakeApiPostEvent: MarathonEvent = ApiPostEvent("192.168.1.1", "http://localhost", fakeAppDefinition)
}
