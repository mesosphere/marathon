package mesosphere.mesos.scale

import play.api.libs.json.JsObject
import play.api.libs.json.Reads._

/**
  * Displays the saved data of [[SingleAppScalingTest]] in a human readable way.
  */
object DisplayAppScalingResults {

  def displayAppInfoScaling(fileName: String): Unit = {
    val appInfos: Seq[JsObject] = ScalingTestResultFiles.readJson[Seq[JsObject]](fileName)

    val header = IndexedSeq("relative time (ms)", "staged", "running", "newRunning/s", "instances")
    var lastTimestamp: Long = 0
    var lastRunning: Long = 0
    val rows = appInfos.map { jsObject: JsObject =>
      val relativeTimestamp = (jsObject \ ScalingTestResultFiles.relativeTimestampMs).as[Long]
      val staged = (jsObject \ "tasksStaged").as[Long]
      val running = (jsObject \ "tasksRunning").as[Long]
      val newRunningPerSecond = 1000.0 * (running - lastRunning) / (relativeTimestamp - lastTimestamp)
      lastTimestamp = relativeTimestamp
      lastRunning = running
      val instances = (jsObject \ "instances").as[Long]

      IndexedSeq(relativeTimestamp, staged, running, newRunningPerSecond.round, instances)
    }

    import DisplayHelpers.right
    DisplayHelpers.printTable(Seq(right, right, right, right, right), DisplayHelpers.withUnderline(header) ++ rows)
  }

  def displayMetrics(fileName: String): Unit = {
    val allMetrics: Seq[JsObject] = ScalingTestResultFiles.readJson[Seq[JsObject]](fileName)

    def subMetric(name: String): Map[String, JsObject] = {
      (allMetrics.last \ name).as[JsObject].value.map {
        case (name, value) => name -> value.as[JsObject]
      }.toMap
    }

    displayMeters(subMetric("meters"))

    println()

    displayHistograms(subMetric("histograms"))

    println()

    displayTimers(subMetric("timers"))
  }

  def shortenName(name: String): String = {
    name.replaceAll("mesosphere\\.marathon", "marathon").replaceAll("org\\.eclipse\\.jetty\\.servlet", "servlet")
  }

  def displayMeters(meters: Map[String, JsObject]): Unit = {
    val header = IndexedSeq("meter", "count", "m15_rate", "m5_rate", "m1_rate", "mean_rate", "units")
    val rows: Seq[IndexedSeq[Any]] = meters.map {
      case (meter: String, jsObject: JsObject) =>
        def d(fieldName: String): Any =
          (jsObject \ fieldName).asOpt[Double].map(_.round).getOrElse("-")

        val units: String = (jsObject \ "units").asOpt[String].getOrElse("-")
        IndexedSeq[Any](
          shortenName(meter),
          d("count"), d("m15_rate"), d("m5_rate"), d("m1_rate"), d("mean_rate"), units)
    }.toSeq

    val sortedRows = rows.sortBy(-_(1).asInstanceOf[Long])

    import DisplayHelpers.{ left, right }
    DisplayHelpers.printTable(
      Seq(left, right, right, right, right, right, left),
      DisplayHelpers.withUnderline(header) ++ sortedRows)
  }

  def displayHistograms(histograms: Map[String, JsObject]): Unit = {
    val header = IndexedSeq("histogram", "count", "mean", "min", "p50", "p75", "p95", "p98", "p99", "p999", "max", "stddev")
    val rows: Seq[IndexedSeq[Any]] = histograms.map {
      case (histogram: String, jsObject: JsObject) =>
        def d(fieldName: String): Any =
          (jsObject \ fieldName).asOpt[Double].map(_.round).getOrElse("-")

        IndexedSeq[Any](
          shortenName(histogram),
          d("count"), d("mean"),
          d("min"), d("p50"), d("p75"), d("p95"), d("p98"), d("p99"), d("p999"), d("max"), d("stddev"))
    }.toSeq

    val sortedRows = rows.sortBy(-_(1).asInstanceOf[Long])

    import DisplayHelpers.{ left, right }
    DisplayHelpers.printTable(
      Seq(left, right, right, right, right, right, right, right, right, right, right, right),
      DisplayHelpers.withUnderline(header) ++ sortedRows)
  }

  def displayTimers(timers: Map[String, JsObject]): Unit = {
    val header = IndexedSeq("timer", "count", "mean", "min", "p50", "p75", "p95", "p98", "p99", "p999", "max", "stddev")
    val rows: Seq[IndexedSeq[Any]] = timers.map {
      case (timer: String, jsObject: JsObject) =>
        def d(fieldName: String): Any =
          (jsObject \ fieldName).asOpt[Double].map(_.round).getOrElse("-")

        IndexedSeq[Any](
          shortenName(timer),
          d("count"), d("mean"),
          d("min"), d("p50"), d("p75"), d("p95"), d("p98"), d("p99"), d("p999"), d("max"), d("stddev"))
    }.toSeq

    val sortedRows = rows.sortBy(-_(1).asInstanceOf[Long])

    import DisplayHelpers.{ left, right }
    DisplayHelpers.printTable(
      Seq(left, right, right, right, right, right, right, right, right, right, right, right),
      DisplayHelpers.withUnderline(header) ++ sortedRows)
  }

  def main(args: Array[String]): Unit = {
    println()
    displayMetrics(SingleAppScalingTest.metricsFile)
    println()
    displayAppInfoScaling(SingleAppScalingTest.appInfosFile)
  }
}
