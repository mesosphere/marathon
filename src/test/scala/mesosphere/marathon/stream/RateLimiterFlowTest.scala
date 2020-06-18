package mesosphere.marathon
package stream

import java.time.{Instant, Duration => JavaDuration}

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock

import scala.concurrent.duration._

class RateLimiterFlowTest extends AkkaUnitTest {
  "does not delay the very first element" in {
    val clock = new SettableClock()

    // if the first element is delayed, then the buffer will receive back-pressure signal and it will be dropped
    Source(List(1, 2))
      .buffer(1, OverflowStrategy.dropTail)
      .via(RateLimiterFlow[Int](100.millis, clock))
      .runWith(Sink.seq)
      .futureValue
      .shouldBe(Seq(1, 2))
  }

  "delay the pulling of elements by the specified rate" in {
    val clock = new SettableClock()

    Given("a stream connected to a rate limiter flow with a rate limit of 1/100ms")
    val (input, output) =
      Source
        .queue[Int](16, OverflowStrategy.fail)
        .via(RateLimiterFlow[Int](100.millis, clock))
        .toMat(Sink.queue())(Keep.both)
        .run

    val start = Instant.now()
    When("the first element is published")
    input.offer(1)

    Then("it should take less than 100ms to come through")
    output.pull().futureValue shouldBe Some(1)
    val e1Time = Instant.now
    JavaDuration.between(start, e1Time).toMillis should be < 100L

    When("another element is published at the same time")
    input.offer(2)
    Then("it should take more than 100ms from the start to come through")
    output.pull().futureValue shouldBe Some(2)
    val e2Time = Instant.now
    JavaDuration.between(start, e2Time).toMillis should be >= 100L

    When("the clock is advanced by 200 ms")
    clock.advanceBy(200.millis)
    And("element 3 is published")
    input.offer(3)

    Then("then element 3 is received less than 100ms after element 2")
    output.pull().futureValue shouldBe Some(3)
    val e3Time = Instant.now
    JavaDuration.between(e2Time, e3Time).toMillis should be >= 100L
  }

  "not deliver a termination signal until all elements are processed" in {
    val input = List(1, 2, 3, 4, 5)
    val output = Source(List(1, 2, 3, 4, 5))
      .via(RateLimiterFlow[Int](50.millis))
      .runWith(Sink.seq)
      .futureValue

    output shouldBe input
  }

}
