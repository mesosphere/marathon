package mesosphere.marathon
package stream

import java.time.{Clock, Duration => JavaDuration}

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Sink, Source}
import mesosphere.AkkaUnitTest
import org.scalatest.Inside

import scala.concurrent.duration._

class RepeaterTest extends AkkaUnitTest with Inside {

  "Repeater" should {
    "Repeat the specified element" in {
      val (sq, elements) = Source.queue[Int](16, OverflowStrategy.fail).preMaterialize()
      val clock = Clock.systemUTC()
      val output = elements.via(Repeater(100.millis)).map { n => (n, clock.instant()) }.runWith(Sink.queue())

      sq.offer(1)
      sq.offer(2)
      inside(Seq.fill(3)(output.pull().futureValue).flatten) {
        case Seq((o1, _), (o2, t2), (o3, t3)) =>
          o1 shouldBe 1
          o2 shouldBe 2
          o3 shouldBe 2

          JavaDuration.between(t2, t3).toMillis shouldBe >= (100L)
      }

      sq.offer(3)
      sq.offer(4)
      sq.complete()

      inside(Seq.fill(2)(output.pull().futureValue).flatten) {
        case Seq((o4, _), (o5, _)) =>
          o4 shouldBe 3
          o5 shouldBe 4
      }
      output.pull().futureValue shouldBe None
    }
  }
}
