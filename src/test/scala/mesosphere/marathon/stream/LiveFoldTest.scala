package mesosphere.marathon
package stream

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Source}
import mesosphere.AkkaUnitTest

class LiveFoldTest extends AkkaUnitTest {
  override def materializerSettings =
    super.materializerSettings.withDispatcher(akka.testkit.CallingThreadDispatcher.Id)

  "it outputs the final result when the stream is completed" in {
    val sum = Source(List(1, 2, 3)).runWith(EnrichedSink.liveFold(0)(_ + _))
    sum.finalResult.futureValue shouldBe (6)
  }

  "it provides access to the current result as each element is fed" in {
    val (input, sum) = Source
      .queue[Int](16, OverflowStrategy.fail)
      .toMat(EnrichedSink.liveFold(0)(_ + _))(Keep.both)
      .run
    sum.readCurrentResult().futureValue shouldBe 0
    input.offer(1)
    sum.readCurrentResult().futureValue shouldBe 1
    input.offer(2)
    sum.readCurrentResult().futureValue shouldBe 3
  }
}
