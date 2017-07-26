package mesosphere.marathon
package metrics

import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import java.time.{ Clock, Duration, Instant }
import kamon.Kamon
import kamon.metric.instrument
import kamon.metric.instrument.Time
import mesosphere.util.CallerThreadExecutionContext

import scala.Exception
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
  * Akka Graph Stage that measures the time from the start of the stream until the end of it, where start is defined as
  * the instant the stream is materialized
  */
private[metrics] class TimedStage[T](histogram: instrument.Histogram, clock: Clock) extends GraphStage[FlowShape[T, T]] {
  val in = Inlet[T]("timer.in")
  val out = Outlet[T]("timer.out")

  @scala.throws[Exception](classOf[Exception])
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {

    val logic = new GraphStageLogic(shape) {
      private val start: Instant = clock.instant

      setHandler(in, new InHandler {
        @scala.throws[Exception](classOf[Exception])
        override def onPush(): Unit = push(out, grab(in))

        @scala.throws[Exception](classOf[Exception])
        override def onUpstreamFinish(): Unit = {
          histogram.record(Duration.between(start, clock.instant).toNanos())
          super.onUpstreamFinish()
        }

        @scala.throws[Exception](classOf[Exception])
        override def onUpstreamFailure(ex: Throwable): Unit = {
          histogram.record(Duration.between(start, clock.instant).toNanos())
          super.onUpstreamFailure(ex)
        }
      })

      setHandler(out, new OutHandler {
        @scala.throws[Exception](classOf[Exception])
        override def onPull(): Unit = pull(in)
      })
    }
    logic
  }

  override def shape: FlowShape[T, T] = FlowShape.of(in, out)
}

private[metrics] case class HistogramTimer(name: String, tags: Map[String, String] = Map.empty,
    unit: Time = Time.Nanoseconds) extends Timer {
  private[metrics] val histogram: instrument.Histogram = Kamon.metrics.histogram(name, tags, unit)

  def apply[T](f: => Future[T]): Future[T] = {
    val start = System.nanoTime()
    val future = try {
      f
    } catch {
      case NonFatal(e) =>
        histogram.record(System.nanoTime() - start)
        throw e
    }
    future.onComplete(_ => histogram.record(System.nanoTime() - start))(CallerThreadExecutionContext.callerThreadExecutionContext)
    future
  }

  def forSource[T, M](f: => Source[T, M])(implicit clock: Clock = Clock.systemUTC): Source[T, M] = {
    val start = System.nanoTime()
    val src = f
    val flow = Flow.fromGraph(new TimedStage[T](histogram, clock))
    val transformed = src.via(flow)
    transformed
  }

  def blocking[T](f: => T): T = {
    val start = System.nanoTime()
    try {
      f
    } finally {
      histogram.record(System.nanoTime() - start)
    }
  }

  def update(value: Long): this.type = {
    histogram.record(value)
    this
  }

  def update(duration: FiniteDuration): this.type = {
    val value = unit match {
      case Time.Nanoseconds => duration.toNanos
      case Time.Milliseconds => duration.toMillis
      case Time.Microseconds => duration.toMicros
      case Time.Seconds => duration.toSeconds
    }
    update(value)
  }
}
