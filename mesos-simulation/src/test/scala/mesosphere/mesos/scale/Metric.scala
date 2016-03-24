package mesosphere.mesos.scale

import java.net.URL

import play.api.libs.json._

import scala.io.Source

/**
  * Base trait for all metrics
  */
sealed trait Metric {
  def name: String
  def mean: Double
}

case class Gauge(name: String, value: Double) extends Metric {
  override def mean: Double = value
}

case class Counter(name: String, count: Int) extends Metric {
  override def mean: Double = count.toDouble
}

case class Histogram(name: String,
                     count: Int,
                     max: Double,
                     mean: Double,
                     min: Double,
                     p50: Double,
                     p75: Double,
                     p95: Double,
                     p98: Double,
                     p99: Double,
                     p999: Double,
                     stddev: Double) extends Metric

case class Meter(name: String,
                 count: Int,
                 m15_rate: Double,
                 m1_rate: Double,
                 m5_rate: Double,
                 mean_rate: Double,
                 units: String) extends Metric {
  override def mean: Double = mean_rate
}

case class Timer(name: String,
                 count: Int,
                 max: Double,
                 mean: Double,
                 min: Double,
                 p50: Double,
                 p75: Double,
                 p95: Double,
                 p98: Double,
                 p99: Double,
                 p999: Double,
                 stddev: Double,
                 m15_rate: Double,
                 m1_rate: Double,
                 m5_rate: Double,
                 mean_rate: Double,
                 duration_units: String,
                 rate_units: String) extends Metric

case class MetricsSample(
    version: String,
    gauges: Seq[Gauge],
    counters: Seq[Counter],
    histograms: Seq[Histogram],
    meters: Seq[Meter],
    timers: Seq[Timer]) {

  def all: Map[String, Seq[Metric]] = Map (
    "gauges" -> gauges,
    "counters" -> counters,
    "gauges" -> gauges,
    "histograms" -> histograms,
    "meters" -> meters,
    "timers" -> timers
  )
}

object MetricsFormat {
  /**
    * Special Reads function, that transform reads a given json object
    *  { "a" : {}, "b" : {} } into an array of form
    *  [ { "name": "a", ... }, { "name": "b", ...} ]
    *  The given reads function is used to read the transformed json into an object.
    */
  def objectRead[T](t: Reads[T]): Reads[Seq[T]] = new Reads[Seq[T]] {
    override def reads(js: JsValue): JsResult[Seq[T]] = {
      JsSuccess(js.as[JsObject].fields.map {
        case (name, value) =>
          val obj = JsObject(value.as[JsObject].fields :+ ("name" -> JsString(name)))
          t.reads(obj).get
      })
    }
  }

  // Json Reads objects for metric structure
  implicit val gaugeReads = objectRead(Json.reads[Gauge])
  implicit val countReads = objectRead(Json.reads[Counter])
  implicit val histogramReads = objectRead(Json.reads[Histogram])
  implicit val meterReads = objectRead(Json.reads[Meter])
  implicit val timerReads = objectRead(Json.reads[Timer])
  implicit val sampleReads: Reads[MetricsSample] = Json.reads[MetricsSample]

  def readMetrics(url: URL): Seq[MetricsSample] = {
    val jsonString = Source.fromURL(url, "UTF-8").mkString
    Json.parse(jsonString).as[Seq[MetricsSample]]
  }

  def readMetric(url: URL): MetricsSample = {
    val jsonString = Source.fromURL(url, "UTF-8").mkString
    Json.parse(jsonString).as[MetricsSample]
  }
}
