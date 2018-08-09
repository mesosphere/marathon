package mesosphere.marathon
package stream

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source}
import mesosphere.AkkaUnitTest

class EnrichedFlowTest extends AkkaUnitTest {
  override def materializerSettings =
    super.materializerSettings.withDispatcher(akka.testkit.CallingThreadDispatcher.Id)

  "combineLatest" should {
    val numbers = Source.queue[Int](16, OverflowStrategy.backpressure)
    val letters = Source.queue[String](16, OverflowStrategy.backpressure)

    class Fixture(eagerComplete: Boolean = true) {
      val ((inputNumbers, inputLetters), output) =
        numbers
          .viaMat(EnrichedFlow.combineLatest(letters, eagerComplete = eagerComplete))(Keep.both)
          .toMat(Sink.queue())(Keep.both)
          .run
    }

    "emit a tuple only once both elements are emitted" in new Fixture() {
      inputNumbers.offer(1)
      inputLetters.offer("a")
      inputNumbers.complete()
      inputLetters.complete()

      output.pull().futureValue shouldBe Some(1 -> "a")
      output.pull().futureValue shouldBe None
    }

    "eagerly close the stream when eagerComplete = true" in new Fixture(eagerComplete = true) {
      inputNumbers.offer(1)
      inputLetters.offer("a")
      inputNumbers.complete()
      inputLetters.offer("b")
      output.pull().futureValue shouldBe Some(1 -> "a")
      output.pull().futureValue shouldBe None
    }

    "submit further updates from one stream when the other closes and eagerComplete = false" in new Fixture(eagerComplete = false) {
      inputNumbers.offer(1)
      inputLetters.offer("a")
      inputNumbers.offer(2)
      inputNumbers.complete()
      inputLetters.offer("b")
      inputLetters.offer("c")
      output.pull().futureValue shouldBe Some(1 -> "a")
      output.pull().futureValue shouldBe Some(2 -> "a")
      output.pull().futureValue shouldBe Some(2 -> "b")
      output.pull().futureValue shouldBe Some(2 -> "c")
      inputLetters.complete()
      output.pull().futureValue shouldBe None
    }
  }
}
