package mesosphere.marathon
package metrics

import java.time.Duration

import kamon.Kamon
import kamon.metric.{Entity, EntitySnapshot}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.CollectionContext
import kamon.util.{MapMerge, MilliTimestamp}

/**
  * Calculates sliding average entity snapshot
  */
class SlidingAverageSnapshot(val averagingWindow: Duration) extends StrictLogging {

  val collectionContext: CollectionContext = Kamon.metrics.buildDefaultCollectionContext

  /**
    * The current summarised snapshot
    */
  private var currentSnapshot: TickMetricSnapshot = TickMetricSnapshot(MilliTimestamp.now, MilliTimestamp.now, Map.empty)

  /**
    * A ring buffer that collects the snapshots
    */
  private var averageRing: Array[TickMetricSnapshot] = ringFactory(Kamon.config)

  /**
    * The current index to the average ring buffer
    */
  private var averageIndex: Int = 0

  /**
    * Rebuilds the average ring, based on the current configuration
    */
  private def ringFactory(config: Config): Array[TickMetricSnapshot] = {
    val tickInterval: Long = config.getDuration("kamon.metric.tick-interval").toMillis
    val fullFrames: Int = (averagingWindow.toMillis.toFloat / tickInterval).ceil.toInt

    // Warn for too low values
    if (fullFrames < 3) {
      logger.warn(s"SlidingAverageReporter applied over a very small window (${fullFrames} frames), consider" +
        " increasing averageWindow or decreasing `kamon.metric.tick-interval`!")
    }

    // Create an array with that many free slots, as the full ticks that fit within the duration requested
    logger.info(s"Initialized sliding average reporter with ${fullFrames} frames")
    Array.fill(fullFrames){null}
  }

  /**
    * Combine all the metrics in the ring buffer and return an
    * @return
    */
  private def combineRingMetrics: TickMetricSnapshot = {
    var rangeFrom: MilliTimestamp = MilliTimestamp.now
    var rangeTo: MilliTimestamp = MilliTimestamp.now
    var combined: Option[Map[Entity, EntitySnapshot]] = None

    // Start processing all the values of the ring buffer in incrementing index
    // starting fro the oldest (the next value from the current index) to the
    // newest (the current index).
    val maxItems: Int = averageRing.length
    for (i: Int <- 0 until maxItems) {
      val idx = (averageIndex + i) % maxItems
      val inst = averageRing(idx)

      // Pass-through null entries
      combined = inst match {
        case null => combined
        case _ => {
          combined match {

            // Initialize all iteration values at first index
            case None => {
              rangeFrom = inst.from
              rangeTo = inst.to
              Some(inst.metrics)
            }

            // Otherwise combine the current metrics and advance the upper range
            case Some(current) => {
              rangeTo = inst.to
              Some(MapMerge.Syntax(current).merge(inst.metrics, (l, r) => l.merge(r, collectionContext)))
            }
          }
        }
      }
    }

    // Compose the snapshot from the combined data
    TickMetricSnapshot(
      rangeFrom,
      rangeTo,
      combined getOrElse Map()
    )
  }

  /**
    * Update the sliding average window with a new frame that should come at fixed intervals,
    * as configured in `kamon.metric.tick-interval`
    *
    * @param snapshot The snaphot received from Kamon
    * @return Returns the averaged values of the metrics
    */
  def updateWithTick(snapshot: TickMetricSnapshot): TickMetricSnapshot = {
    // Put the tick metrics on the ring buffer
    averageRing.update(averageIndex, snapshot)
    averageIndex = (averageIndex + 1) % averageRing.length

    // Update current snapshot and return it
    currentSnapshot = combineRingMetrics
    currentSnapshot
  }

  /**
    * Collect the current snapshot
    * @return
    */
  def snapshot(): TickMetricSnapshot = currentSnapshot

  /**
    * Get the size of the ring
    */
  def ringSize(): Int = averageRing.length
}
