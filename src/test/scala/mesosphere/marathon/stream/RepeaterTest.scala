package mesosphere.marathon
package stream

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Source }
import java.util.concurrent.Executors
import mesosphere.{ AkkaUnitTest, WhenEnvSet }
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Random
import scala.concurrent.duration._
import scala.concurrent.duration.Duration

class RepeaterTest extends AkkaUnitTest {
  "it propagates results to streams that register at any time during the stream" in {
    val (input, newSubscribers) = Source.queue[Int](32, OverflowStrategy.fail)
      .toMat(Repeater.sink(32, OverflowStrategy.fail))(Keep.both)
      .run

    val result1 = newSubscribers.runWith(Sink.last)
    input.offer(1)
    val result2 = newSubscribers.runWith(Sink.last)
    input.complete()
    val result3 = newSubscribers.runWith(Sink.last)
    result1.futureValue shouldBe 1
    result2.futureValue shouldBe 1
    result3.futureValue shouldBe 1
  }

  /* This test takes a while to run. If you modify Repeater's code, you should run this at least once to ensure there
   * are no races. */
  "it behaves identically when called in stochastic, asynchronous contexts" taggedAs WhenEnvSet("ASYNC_STRESS") in {
    val executor = Executors.newFixedThreadPool(32)
    val stressTestEc = ExecutionContext.fromExecutor(executor)
    try {
      val s = Source(1 to 10000)
        .mapAsyncUnordered(8) { i =>
          val (input, newSubscribers) = Source.queue[Int](32, OverflowStrategy.fail)
            .toMat(Repeater.sink(32, OverflowStrategy.fail))(Keep.both)
            .run

          Future {
            input.offer(2)
            Thread.sleep(Random.nextInt(5).toLong)
            input.offer(1)
            Thread.sleep(Random.nextInt(5).toLong)
            input.complete()
          }(stressTestEc)

          def getSubscriber =
            Future {
              /* This checks to make sure there are no data races during the following transitions:
               * - An element is published
               * - The last element is published
               * - The stream is closed */
              Thread.sleep(Random.nextInt(5).toLong)
              newSubscribers.runWith(Sink.last)
            }(stressTestEc)

          // We materialize multiple subscribers in parallel to assert that there are no data races causing subscriptions to drop
          Future.sequence(List(getSubscriber, getSubscriber, getSubscriber))
        }
        .mapConcat(identity)
        .mapAsync(1)(identity)
        .idleTimeout(patienceConfig.timeout)
        .runFold(Set.empty[Int]) { _ + _ }

      Await.result(s, Duration.Inf) shouldBe Set(1)
    } finally {
      executor.shutdownNow()
    }
  }

}
