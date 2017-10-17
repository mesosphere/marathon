package mesosphere.marathon
package metrics

import akka.Done
import akka.actor.{ Actor, ActorRef, ActorRefFactory, Props }
import akka.stream.scaladsl.Source
import java.time.Clock
import kamon.Kamon
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.Histogram.DynamicRange
import kamon.metric.instrument.{ CollectionContext, Time, UnitOfMeasurement }
import kamon.metric.{ Entity, SubscriptionFilter, instrument }
import kamon.util.{ MapMerge, MilliTimestamp }

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait Counter {
  def increment(): Counter
  def increment(times: Long): Counter
}

trait Gauge {
  def value(): Long
  def increment(by: Long = 1): Gauge
  def decrement(by: Long = 1): Gauge
}

trait SettableGauge extends Gauge {
  def setValue(value: Long): SettableGauge
}

trait Histogram {
  def record(value: Long): Histogram
  def record(value: Long, count: Long): Histogram
}

trait MinMaxCounter {
  def increment(): MinMaxCounter
  def increment(times: Long): MinMaxCounter
  def decrement(): MinMaxCounter
  def decrement(times: Long): MinMaxCounter
  def refreshValues(): MinMaxCounter
  def record(value: Long): MinMaxCounter
  def record(value: Long, count: Long): MinMaxCounter
}

trait Timer {
  def apply[T](f: => Future[T]): Future[T]
  def forSource[T, M](f: => Source[T, M])(implicit clock: Clock = Clock.systemUTC): Source[T, M]
  def blocking[T](f: => T): T
  def update(value: Long): Timer
  def update(duration: FiniteDuration): Timer
}

object AcceptAllFilter extends SubscriptionFilter {
  override def accept(entity: Entity): Boolean = true
}

object Metrics {
  implicit class KamonCounter(val counter: instrument.Counter) extends Counter {
    override def increment(): KamonCounter = {
      counter.increment()
      this
    }
    override def increment(times: Long): KamonCounter = {
      counter.increment(times)
      this
    }
  }

  def counter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): Counter = {
    Kamon.metrics.counter(name(prefix, `class`, metricName), tags, unit)
  }

  private implicit class KamonGauge(val gauge: instrument.Gauge) extends Gauge {
    override def value(): Long = gauge.value()
    override def increment(by: Long): this.type = {
      gauge.increment(by)
      this
    }
    override def decrement(by: Long): this.type = {
      gauge.decrement(by)
      this
    }
  }
  def gauge(prefix: MetricPrefix, `class`: Class[_], metricName: String, currentValue: () => Long,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): Gauge = {
    Kamon.metrics.gauge(name(prefix, `class`, metricName), tags, unit)(currentValue)
  }

  def atomicGauge(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): SettableGauge = {
    AtomicGauge(name(prefix, `class`, metricName), unit, tags)
  }

  implicit class KamonHistogram(val histogram: instrument.Histogram) extends Histogram {
    override def record(value: Long): KamonHistogram = {
      histogram.record(value)
      this
    }

    override def record(value: Long, count: Long): KamonHistogram = {
      histogram.record(value, count)
      this
    }
  }

  def histogram(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown,
    dynamicRange: DynamicRange): Histogram = {
    Kamon.metrics.histogram(name(prefix, `class`, metricName), tags, unit, dynamicRange)
  }

  implicit class KamonMinMaxCounter(val counter: instrument.MinMaxCounter) extends MinMaxCounter {
    override def increment(): KamonMinMaxCounter = {
      counter.increment()
      this
    }

    override def increment(times: Long): KamonMinMaxCounter = {
      counter.increment(times)
      this
    }

    override def decrement(): KamonMinMaxCounter = {
      counter.decrement()
      this
    }

    override def decrement(times: Long): KamonMinMaxCounter = {
      counter.decrement(times)
      this
    }

    override def refreshValues(): KamonMinMaxCounter = {
      counter.refreshValues()
      this
    }

    override def record(value: Long): KamonMinMaxCounter = {
      counter.record(value)
      this
    }

    override def record(value: Long, count: Long): KamonMinMaxCounter.this.type = {
      counter.record(value, count)
      this
    }
  }

  def minMaxCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): MinMaxCounter = {
    Kamon.metrics.minMaxCounter(name(prefix, `class`, metricName), tags, unit)
  }

  def timer(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: Time = Time.Nanoseconds): Timer = {
    HistogramTimer(name(prefix, `class`, metricName), tags, unit)
  }

  def subscribe(actorRef: ActorRef, filter: SubscriptionFilter = AcceptAllFilter): Done = {
    Kamon.metrics.subscribe(filter, actorRef)
    Done
  }

  private[this] var metrics: TickMetricSnapshot = {
    val now = MilliTimestamp.now
    TickMetricSnapshot(now, now, Map.empty)
  }

  // returns the current snapshot. Doesn't collect until `start` is called
  def snapshot(): TickMetricSnapshot = metrics

  // Starts collecting snapshots.
  def start(actorRefFactory: ActorRefFactory): Done = {
    class SubscriberActor() extends Actor {
      val collectionContext: CollectionContext = Kamon.metrics.buildDefaultCollectionContext
      override def receive: Actor.Receive = {
        case TickMetricSnapshot(_, to, tickMetrics) =>
          val combined = MapMerge.Syntax(metrics.metrics).merge(tickMetrics, (l, r) => l.merge(r, collectionContext))
          val combinedSnapshot = TickMetricSnapshot(metrics.from, to, combined)
          metrics = combinedSnapshot
      }
    }
    subscribe(actorRefFactory.actorOf(Props(classOf[SubscriberActor])))
  }
}
