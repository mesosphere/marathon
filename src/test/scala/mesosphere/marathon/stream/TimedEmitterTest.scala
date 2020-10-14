package mesosphere.marathon
package stream

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.util.StreamHelpers
import org.scalatest.Inside

import scala.concurrent.duration._

class TimedEmitterTest extends AkkaUnitTest with Inside {

  import TimedEmitter.{Inactive, Active}

  "TimedEmitter" should {
    "emit elements as completed if a timestamp is in the past" in {
      val f = new Fixture

      f.clock.instant()

      val result = Source(List("a" -> Some(f.clock.instant().minusMillis(1))))
        .via(f.timedEmitter)
        .runWith(Sink.head)

      result.futureValue shouldBe Inactive("a")
    }

    "re-emits elements as active if a new timestamp is received" in {
      val f = new Fixture

      f.clock.instant()

      val (duration, result) = measuring {
        Source(List("a" -> Some(f.clock.instant().plusSeconds(120)), "a" -> Some(f.clock.instant().plusMillis(500))))
          .concat(StreamHelpers.sourceNever) // prevent stream from closing
          .via(f.timedEmitter)
          .take(3)
          .runWith(Sink.seq)
          .futureValue
      }

      inside(result) {
        case Seq(output1, output2, output3) =>
          output1 shouldBe Active("a")
          output2 shouldBe Active("a")
          output3 shouldBe Inactive("a")
      }

      duration.toMillis should be >= 500L
    }
  }

  def measuring[T](fn: => T): (Duration, T) = {
    val now = System.currentTimeMillis()
    val result = fn
    ((System.currentTimeMillis() - now).millis, result)
  }

  class Fixture {
    val clock = new SettableClock

    def timedEmitter: Flow[TimedEmitter.Input[String], TimedEmitter.EventState[String], NotUsed] =
      Flow.fromGraph(new TimedEmitter[String](clock))
  }

}
