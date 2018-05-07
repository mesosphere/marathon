package mesosphere.marathon
package metrics

import java.time.Duration

import kamon.Kamon
import kamon.metric.{ Entity, EntitySnapshot }
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.CollectionContext
import kamon.util.{ MapMerge, MilliTimestamp }

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
  private val averageRing: Array[TickMetricSnapshot] = ringFactory(Kamon.config)

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

    // It does not make sense having a sliding average window with less than
    // two frames. Let the user know just in case this happened by a misconfiguration.
    if (fullFrames < 2) {
      logger.warn(s"SlidingAverageReporter applied over a very small window (${fullFrames} frames), consider" +
        " increasing averageWindow or decreasing `kamon.metric.tick-interval`!")
    }

    // Create an array with that many free slots, as the full ticks that fit within the duration requested
    logger.info(s"Initialized sliding average reporter with ${fullFrames} frames")
    Array.fill(fullFrames){ null }
  }

  /**
    * Combine all the metrics in the ring buffer and return an
    * @return
    */
  private def combineRingMetrics: TickMetricSnapshot = {
    var rangeFrom: MilliTimestamp = MilliTimestamp.now
    var rangeTo: MilliTimestamp = MilliTimestamp.now
    var combined: Option[Map[Entity, EntitySnapshot]] = None

    // To combine the metrics we need to iterate over the ring buffer, performing
    // the following operations:
    //
    // - Find the oldest and the newest timestamp, that will be used as the snapshot
    //   range for our combined metrics.
    // - For every snapshot, merge every metric with the current
    //
    // Since we are putting items in the ring buffer in order, we can exploit the indices
    // to quickly identify the oldest and the newest entry:
    //
    // - Since we post-increment when adding items on the ring buffer, the item with
    //   index `averageIndex - 1` is the newest one.
    // - Consequently, the item with index `averageIndex` is the oldest one.
    //
    // In the following loop we are starting from the oldest item and walk our way to the
    // newest item. That's why we start with `idx = averageIndex` and we advance it
    // as many times as the items in the ring buffer, effectively reaching to the
    // newest item on `idx = averageIndex - 1`
    //

    val maxItems: Int = averageRing.length
    var i: Int = 0
    while (i < maxItems) {
      val idx = (averageIndex + i) % maxItems
      val inst = averageRing(idx)

      //
      // The `null` items are items not yet initialized and we
      // therefore consider them transparent.
      //
      combined = inst match {
        case null => combined
        case _ => {
          combined match {

            //
            // The first record we find is the oldest one. So we are using it's
            // timestamp as the `rangeFrom` and we initialize the `combined`
            // snapshot with it's metrics.
            //
            // Just in case this is the last item we ever find, we are also
            // populating `rangeTo` with the correct time bounds.
            //
            case None => {
              rangeFrom = inst.from
              rangeTo = inst.to
              Some(inst.metrics)
            }

            //
            // Otherwise combine the current metrics and advance `rangeTo`
            //
            case Some(current) => {
              rangeTo = inst.to
              Some(MapMerge.Syntax(current).merge(inst.metrics, (l, r) => l.merge(r, collectionContext)))
            }
          }
        }
      }

      // Advance index of while loop
      i += 1
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
    * Return the computed snapshot
    * @return
    */
  def snapshot(): TickMetricSnapshot = currentSnapshot

  /**
    * Get the size of the ring
    */
  def ringSize(): Int = averageRing.length
}
