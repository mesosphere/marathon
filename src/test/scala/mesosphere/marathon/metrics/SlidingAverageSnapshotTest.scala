package mesosphere.marathon.metrics

import java.time.Duration

import akka.actor.{Actor, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.util.MilliTimestamp
import mesosphere.UnitTest

import scala.concurrent.Await

/**
  * Since some tests are repeating the same actions, the `SlidingAverageSnapshotTestHandler` trait
  * provides the interface the test cases can implement
  */
trait SlidingAverageSnapshotTestHandler {

  /**
    * Called when Kamon is initialized
    * The test handler may now instantiate an instrument
    */
  def init(): Unit

  /**
    * Called on every tick so the test can push their values at known intervals
    *
    * @param snapshot The current tickSnapshot from Kamon
    * @return Return TRUE to keep running or FALSE to complete the test
    */
  def tick(snapshot: TickMetricSnapshot): Boolean
}

class SlidingAverageSnapshotTest extends UnitTest {

  "Sliding Average Snapshot" should {

    "should correctly be configured using the `kamon.metric.tick-interval` parameter" in {
      // Start Kamon with custom parameters
      Kamon.start(ConfigFactory.parseString(
        "kamon.metric.tick-interval = 123"
      ).withFallback(ConfigFactory.load()))

      // Initialize sliding average window with 4 frames
      val win: SlidingAverageSnapshot = new SlidingAverageSnapshot(Duration.ofMillis(123 * 4))
      win.ringSize shouldBe 4
    }

    "should correctly average histograms" in {
      var mostRecentTick: MilliTimestamp = MilliTimestamp.now
      val metrics: TickMetricSnapshot = SlidingAverageSnapshotTest.runTestAndCollectMetrics(
        tickIntervalMs = 100,
        averagingWindowMs = 500,
        new SlidingAverageSnapshotTestHandler {
          private var histogram: kamon.metric.instrument.Histogram = _
          private var index: Int = 0

          // When Kamon is ready, get a histogram
          override def init(): Unit = {
            histogram = Kamon.metrics.histogram("someHistogram")
          }

          // Put 10 samples (x100 ms step = 1 second) and exit on the 11th
          override def tick(snapshot: TickMetricSnapshot): Boolean = {
            mostRecentTick = MilliTimestamp.now
            if (index > 10) false
            else {
              histogram.record(index)
              index += 1
              true
            }
          }
        }
      )

      // The the `to` value should be close to our current time (100 ms tolerance)
      (mostRecentTick.millis - metrics.to.millis).toInt should be < 100

      // Time range from the metrics should be within the averaging window (10 ms tolerance)
      (metrics.to.millis - metrics.from.millis).toInt should be(490 +- 510)

      // The values in the histogram should contain the last 5 measurements and nothing
      // from the previous ones
      metrics.metrics.values.head.histograms.values.head.recordsIterator
        .toStream.map(v => (v.level, v.count)) should be(Seq(
        (6, 1), (7, 1), (8, 1), (9, 1), (10, 1)
      ))
    }

    "should monotonically increase counters" in {
      var mostRecentTick: MilliTimestamp = MilliTimestamp.now
      val metrics: TickMetricSnapshot = SlidingAverageSnapshotTest.runTestAndCollectMetrics(
        tickIntervalMs = 100,
        averagingWindowMs = 500,
        new SlidingAverageSnapshotTestHandler {
          private var counter: kamon.metric.instrument.Counter = _
          private var index: Int = 0

          // When Kamon is ready, get a histogram
          override def init(): Unit = {
            counter = Kamon.metrics.counter("someCounter")
          }

          // Increment the counter by 10 times over the course of 1 second
          override def tick(snapshot: TickMetricSnapshot): Boolean = {
            mostRecentTick = MilliTimestamp.now
            if (index > 10) false
            else {
              counter.increment()
              index += 1
              true
            }
          }
        }
      )

      // The the `to` value should be close to our current time (100 ms tolerance)
      (mostRecentTick.millis - metrics.to.millis).toInt should be < 100

      // Time range from the metrics should be within the averaging window (10 ms tolerance)
      (metrics.to.millis - metrics.from.millis).toInt should be(490 +- 510)

      // The counter takes in account the last 5 values in the window
      metrics.metrics.values.head.counters.head._2.count.toInt should be (5)
    }

  }
}

object SlidingAverageSnapshotTest {

  /**
    * Helper function to start an actor system, configure Kamon, run the tests and wait for the actor system
    * to be shut down before returning the metrics collected so far by the averaging window snasphot
    *
    * @param tickIntervalMs    How frequently Kamon should call the tick function (in ms)
    * @param averagingWindowMs How long is the averaging window (in ms)
    * @param testHandler       The handler for this test case
    * @return Returns the final
    */
  def runTestAndCollectMetrics(tickIntervalMs: Long,
                               averagingWindowMs: Long,
                               testHandler: SlidingAverageSnapshotTestHandler): TickMetricSnapshot = {
    val system = ActorSystem()

    // Configure Kamon and the averaging window with the arguments given
    Kamon.start(ConfigFactory
      .parseString(s"kamon.metric.tick-interval = $tickIntervalMs").withFallback(ConfigFactory.load()))
    val averagingSnapshot: SlidingAverageSnapshot = new SlidingAverageSnapshot(Duration.ofMillis(averagingWindowMs))

    // The metrics at the end of the test
    var metrics: TickMetricSnapshot = TickMetricSnapshot(MilliTimestamp.now, MilliTimestamp.now, Map())

    // Now that Kamon is ready we can initialize the test handler
    testHandler.init()

    // Register an actor that is going to receive TickMetricSnapshots and advance the tests
    // at the same time, in order to mitigate delay-induced race conditions on the test
    class SubscriberActor() extends Actor {
      override def receive: Actor.Receive = {
        case snapshot: TickMetricSnapshot =>
          // Collect average from the previous run and call out to start new run
          metrics = averagingSnapshot.updateWithTick(snapshot)
          if (!testHandler.tick(snapshot)) system.terminate()
      }
    }
    Kamon.metrics.subscribe(AcceptAllFilter, system.actorOf(Props(new SubscriberActor)))

    // Wait for the system to be terminated (wait a bit more than the test duration)
    import scala.concurrent.duration._
    Await.result(system.whenTerminated, 30 seconds)

    // Shut down kamon when done
    Kamon.shutdown()

    // Return the metrics collected
    metrics
  }

}