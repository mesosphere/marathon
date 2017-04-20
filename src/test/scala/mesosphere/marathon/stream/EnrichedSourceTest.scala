package mesosphere.marathon
package stream

import akka.event.EventStream
import akka.stream.OverflowStrategy
import mesosphere.AkkaUnitTest

class EnrichedSourceTest extends AkkaUnitTest {

  "eventBusSource receives items matching the specified runtime class" in {
    val bus = new EventStream(system)
    val source = EnrichedSource.eventBusSource(
      classOf[String], bus, bufferSize = Int.MaxValue, overflowStrategy = OverflowStrategy.fail)

    val result = source.take(5).runWith(Sink.seq)
    val data = Seq("a", "b", "c", "d", "e")
    data.zipWithIndex.foreach {
      case (string, idx) =>
        bus.publish(string)
        bus.publish(Some(idx))
    }

    result.futureValue shouldBe Seq("a", "b", "c", "d", "e")
  }
}
