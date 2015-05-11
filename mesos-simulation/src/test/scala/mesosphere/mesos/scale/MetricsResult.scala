package mesosphere.mesos.scale

import play.api.libs.json._
import org.apache.commons.io.FileUtils
import java.io.File

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

case class Histogram(
  name: String,
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

case class Meter(
    name: String,
    count: Int,
    m15_rate: Double,
    m1_rate: Double,
    m5_rate: Double,
    mean_rate: Double,
    units: String) extends Metric {
  override def mean: Double = mean_rate
}

case class Timer(
  name: String,
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
    relativeTimestampMs: Long,
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

object MetricsResult {

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

  def readMetrics(file: File): Seq[MetricsSample] = {
    val jsonString = FileUtils.readFileToString(file)
    Json.parse(jsonString).as[Seq[MetricsSample]]
  }

  /**
    * Compare 2 Metrics Samples and filter all metrics, that get deteriorated by given factor.
    * @param baseLine the base line to compare the values
    * @param sample the metric samples to compare
    * @param factor the deterioration factor. 1 means not worse than the base.
    * @return all deteriorated metrics (before, after)
    */
  def filterDeteriorated(baseLine: MetricsSample, sample: MetricsSample, factor: Double): Map[Metric, Metric] = {
    for {
      (name, metricsBase) <- baseLine.all
      metricsSample <- sample.all.get(name).toList
      metricBase <- metricsBase if metricBase.mean > 0
      metricSample <- metricsSample.find(_.name == metricBase.name)
      if (metricSample.mean / metricBase.mean) > factor
    } yield metricBase -> metricSample
  }

  def filterDeteriorated(beforeFile: File, afterFile: File, deterioration: Double): Map[Metric, Metric] = {
    //only compare last
    filterDeteriorated(readMetrics(beforeFile).last, readMetrics(afterFile).last, deterioration)
  }

  def main(args: Array[String]) {
    if (args.length == 3) {
      val deteriorated = filterDeteriorated(new File(args(0)), new File(args(1)), args(2).toDouble)
      if (deteriorated.nonEmpty) {
        val message = deteriorated.map{
          case (b, a) =>
            s"${b.name}: ${b.mean} -> ${a.mean} [${(a.mean / b.mean * 100).toInt}%]"
        }.mkString("\n")
        throw new IllegalStateException(message)
      }
    }
    else {
      println(
        """Usage:
          | MetricsResult <file_base> <file_sample> <deterioration_factor>"
          | file_base: the file with the base metrics
          | file_sample: the file with the actual sampled metrics
          | deterioration_factor: number [0..] where 1 means the same as the base line
        """.stripMargin)
      sys.exit(1)
    }
  }
}

