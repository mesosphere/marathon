package mesosphere.marathon
package metrics

import mesosphere.UnitTest
import scala.concurrent.duration._
import kamon.metric.instrument.Time

class HistogramTimerTest extends UnitTest {
  "durationToUnits handles unknown time units" in {
    HistogramTimer.durationToUnits(10.seconds, Time(Time.Seconds.factor, "test seconds")) shouldBe 10L
  }
}
