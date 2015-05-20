package mesosphere.mesos.scale

import java.net.URL

import mesosphere.mesos.scale.MetricsFormat._

import scala.collection.immutable.Iterable

/**
  * Compare Metrics Samples and find all metrics, that get deteriorated by given factor.
  */
object FindDeterioratedMetrics {

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

  def filterDeteriorated(before: URL, after: URL, deterioration: Double): Map[Metric, Metric] = {
    //only compare last
    filterDeteriorated(readMetrics(before).last, readMetrics(after).last, deterioration)
  }
  /**
    * FindDeterioratedMetrics <file_base> <file_sample> <deterioration_factor>
    *  url_base: the file with the base metrics
    *  url_sample: the file with the actual sampled metrics
    *  deterioration_factor: number [0..] where 1 means the same as the base line
    *
    * @param args three args expected: baseFile sampleFile factor
    */
  def main(args: Array[String]) {
    def printSlope(metrics: Map[Metric, Metric]): Unit = {
      import DisplayHelpers._
      val header = Vector("Metric", "Base", "Sample", "Increase in %")
      val rows: Iterable[Vector[String]] = metrics.map {
        case (a, b) => Vector(a.name, a.mean, b.mean, (b.mean / a.mean * 100).toInt - 100).map(_.toString)
      }
      printTable(Seq(left, right, right, right), withUnderline(header) ++ rows.toSeq)
    }

    if (args.length == 3) {
      println("\n\nMetrics that got worse (deterioration factor == 1):")
      printSlope(filterDeteriorated(new URL(args(0)), new URL(args(1)), 1))
      println(s"\n\nMetrics that got deteriorated (deterioration factor == ${args(2)}):")
      val deteriorated = filterDeteriorated(new URL(args(0)), new URL(args(1)), args(2).toDouble)
      if (deteriorated.nonEmpty) {
        printSlope(deteriorated)
        throw new IllegalStateException(s"Sample is deteriorated according to deterioration factor")
      }
    }
    else {
      println(
        """Usage:
          | FindDeterioratedMetrics <file_base> <file_sample> <deterioration_factor>"
          | file_base: the file with the base metrics
          | file_sample: the file with the actual sampled metrics
          | deterioration_factor: number [0..] where 1 means the same as the base line
        """.stripMargin)
      sys.exit(1)
    }
  }
}

