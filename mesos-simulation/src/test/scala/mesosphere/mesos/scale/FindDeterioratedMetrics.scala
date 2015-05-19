package mesosphere.mesos.scale

import java.io.File
import MetricsFormat._

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

  def filterDeteriorated(beforeFile: File, afterFile: File, deterioration: Double): Map[Metric, Metric] = {
    //only compare last
    filterDeteriorated(readMetrics(beforeFile).last, readMetrics(afterFile).last, deterioration)
  }

  /**
    * FindDeterioratedMetrics <file_base> <file_sample> <deterioration_factor>
    *  file_base: the file with the base metrics
    *  file_sample: the file with the actual sampled metrics
    *  deterioration_factor: number [0..] where 1 means the same as the base line
    *
    * @param args three args expected: baseFile sampleFile factor
    */
  def main(args: Array[String]) {
    def slope(a: Metric, b: Metric): String = s"${a.name}: ${a.mean} -> ${b.mean} [${(b.mean / a.mean * 100).toInt}%]"
    if (args.length == 3) {
      println("Metrics that got worse (deterioration factor == 1):")
      println(filterDeteriorated(new File(args(0)), new File(args(1)), 1).map(w => slope(w._1, w._2)).mkString("\n"))
      println(s"Metrics that got deteriorated (deterioration factor == ${args(2)}):")
      val deteriorated = filterDeteriorated(new File(args(0)), new File(args(1)), args(2).toDouble)
      if (deteriorated.nonEmpty) {
        val message = deteriorated.map(w => slope(w._1, w._2)).mkString("\n")
        println(message)
        throw new IllegalStateException(message)
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

