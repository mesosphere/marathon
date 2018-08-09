package mesosphere.marathon
package metrics

import java.time.Duration

import kamon.Kamon
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.util.MilliTimestamp
import mesosphere.UnitTest
import mesosphere.marathon.metrics.deprecated.SlidingAverageSnapshot

class SlidingAverageSnapshotTest extends UnitTest {

  "Sliding Average Snapshot" should {

    "should correctly operate on empty ring buffer" in {
      val win: SlidingAverageSnapshot = SlidingAverageSnapshotTest.ringFactory(4)
      val s: TickMetricSnapshot = win.snapshot()

      // The time span should be zero
      s.from shouldBe s.to
    }

    "should correctly operate on half-full ring buffer" in {
      val win: SlidingAverageSnapshot = SlidingAverageSnapshotTest.ringFactory(4)
      val ts0: MilliTimestamp = MilliTimestamp.now
      val ts1: MilliTimestamp = MilliTimestamp(ts0.millis + 100)

      // Insert snapshot
      win.updateWithTick(TickMetricSnapshot(ts0, ts1, Map()))

      // The time span should be of the first snapshot
      val s: TickMetricSnapshot = win.snapshot()
      s.from shouldBe ts0
      s.to shouldBe ts1
    }

    "should correctly operate on a full ring buffer" in {
      val win: SlidingAverageSnapshot = SlidingAverageSnapshotTest.ringFactory(4)
      val ts0: MilliTimestamp = MilliTimestamp.now
      val ts1: MilliTimestamp = MilliTimestamp(ts0.millis + 100)
      val ts2: MilliTimestamp = MilliTimestamp(ts1.millis + 100)
      val ts3: MilliTimestamp = MilliTimestamp(ts2.millis + 100)
      val ts4: MilliTimestamp = MilliTimestamp(ts3.millis + 100)

      // Insert snapshots
      win.updateWithTick(TickMetricSnapshot(ts0, ts1, Map()))
      win.updateWithTick(TickMetricSnapshot(ts1, ts2, Map()))
      win.updateWithTick(TickMetricSnapshot(ts2, ts3, Map()))
      win.updateWithTick(TickMetricSnapshot(ts3, ts4, Map()))

      // The time span should at the bounds of the first and last snapshot
      val s: TickMetricSnapshot = win.snapshot()
      s.from shouldBe ts0
      s.to shouldBe ts4
    }

    "should correctly operate on an overflown ring buffer" in {
      val win: SlidingAverageSnapshot = SlidingAverageSnapshotTest.ringFactory(4)
      val ts0: MilliTimestamp = MilliTimestamp.now
      val ts1: MilliTimestamp = MilliTimestamp(ts0.millis + 100)
      val ts2: MilliTimestamp = MilliTimestamp(ts1.millis + 100)
      val ts3: MilliTimestamp = MilliTimestamp(ts2.millis + 100)
      val ts4: MilliTimestamp = MilliTimestamp(ts3.millis + 100)
      val ts5: MilliTimestamp = MilliTimestamp(ts4.millis + 100)

      // Insert snapshots
      win.updateWithTick(TickMetricSnapshot(ts0, ts1, Map()))
      win.updateWithTick(TickMetricSnapshot(ts1, ts2, Map()))
      win.updateWithTick(TickMetricSnapshot(ts2, ts3, Map()))
      win.updateWithTick(TickMetricSnapshot(ts3, ts4, Map()))
      win.updateWithTick(TickMetricSnapshot(ts4, ts5, Map()))

      // The time span should at the bounds of the last four snapshots
      val s: TickMetricSnapshot = win.snapshot()
      s.from shouldBe ts1
      s.to shouldBe ts5
    }

  }

}

object SlidingAverageSnapshotTest {

  /**
    * Utility function to instantiate a `SlidingAverageSnapshot` class, having the correct
    * number of frames in it's buffer.
    *
    * @param frameCount The number of frames desired in the average ring buffer
    * @return Returns a new instance of `SlidingAverageSnapshot`
    */
  def ringFactory(frameCount: Int): SlidingAverageSnapshot = {
    val tickInterval: Long = Kamon.config.getDuration("kamon.metric.tick-interval").toMillis
    new SlidingAverageSnapshot(Duration.ofMillis(tickInterval * frameCount))
  }

}
