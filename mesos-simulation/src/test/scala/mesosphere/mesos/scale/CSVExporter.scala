package mesosphere.mesos.scale

import java.io.{ FileWriter, File }

import mesosphere.marathon.io.IO

/**
  * This class is used to export metrics to CSV.
  *
  * Usage: CSVExporter dir [,dir]*
  * Every directory given should hold a list of json files with collected metrics.
  * The files are sorted by name and define the ordering in time.
  */
object CSVExporter {

  case class Row(name: String, values: Seq[Option[Double]])

  def toRows(directory: File): Seq[Row] = {
    val metrics = directory.listFiles().toSeq.filter(_.getName.endsWith("json")).sortBy(_.getName).map { f => MetricsFormat.readMetric(f.toURI.toURL) }
    val countr = metrics.flatMap(_.counters.map(_.name)).distinct.sorted
    val gauges = metrics.flatMap(_.gauges.map(_.name)).distinct.sorted
    val histos = metrics.flatMap(_.histograms.map(_.name)).distinct.sorted
    val meters = metrics.flatMap(_.meters.map(_.name)).distinct.sorted
    val timers = metrics.flatMap(_.timers.map(_.name)).distinct.sorted

    Seq (
      countr.map { name => Row(name + ".count", metrics.map(_.counters.find(_.name == name).map(_.count.toDouble))) },
      gauges.map { name => Row(name + ".value", metrics.map(_.gauges.find(_.name == name).map(_.value))) },
      histos.map { name => Row(name + ".count", metrics.map(_.histograms.find(_.name == name).map(_.count.toDouble))) },
      histos.map { name => Row(name + ".mean", metrics.map(_.histograms.find(_.name == name).map(_.mean))) },
      histos.map { name => Row(name + ".p50", metrics.map(_.histograms.find(_.name == name).map(_.p50))) },
      histos.map { name => Row(name + ".p75", metrics.map(_.histograms.find(_.name == name).map(_.p75))) },
      histos.map { name => Row(name + ".p95", metrics.map(_.histograms.find(_.name == name).map(_.p95))) },
      histos.map { name => Row(name + ".p98", metrics.map(_.histograms.find(_.name == name).map(_.p98))) },
      histos.map { name => Row(name + ".p99", metrics.map(_.histograms.find(_.name == name).map(_.p99))) },
      histos.map { name => Row(name + ".p999", metrics.map(_.histograms.find(_.name == name).map(_.p999))) },
      histos.map { name => Row(name + ".min", metrics.map(_.histograms.find(_.name == name).map(_.min))) },
      histos.map { name => Row(name + ".max", metrics.map(_.histograms.find(_.name == name).map(_.max))) },
      histos.map { name => Row(name + ".stddev", metrics.map(_.histograms.find(_.name == name).map(_.stddev))) },
      meters.map { name => Row(name + ".count", metrics.map(_.meters.find(_.name == name).map(_.count.toDouble))) },
      meters.map { name => Row(name + ".mean_rate", metrics.map(_.meters.find(_.name == name).map(_.mean_rate))) },
      meters.map { name => Row(name + ".m1", metrics.map(_.meters.find(_.name == name).map(_.m1_rate))) },
      meters.map { name => Row(name + ".m5", metrics.map(_.meters.find(_.name == name).map(_.m5_rate))) },
      meters.map { name => Row(name + ".m15", metrics.map(_.meters.find(_.name == name).map(_.m15_rate))) },
      timers.map { name => Row(name + ".count", metrics.map(_.timers.find(_.name == name).map(_.count.toDouble))) },
      timers.map { name => Row(name + ".mean", metrics.map(_.timers.find(_.name == name).map(_.mean))) },
      timers.map { name => Row(name + ".meanRate", metrics.map(_.timers.find(_.name == name).map(_.mean_rate))) },
      timers.map { name => Row(name + ".m1_rate", metrics.map(_.timers.find(_.name == name).map(_.m1_rate))) },
      timers.map { name => Row(name + ".m5_rate", metrics.map(_.timers.find(_.name == name).map(_.m5_rate))) },
      timers.map { name => Row(name + ".m15_rate", metrics.map(_.timers.find(_.name == name).map(_.m15_rate))) },
      timers.map { name => Row(name + ".min", metrics.map(_.timers.find(_.name == name).map(_.min))) },
      timers.map { name => Row(name + ".max", metrics.map(_.timers.find(_.name == name).map(_.max))) },
      timers.map { name => Row(name + ".p50", metrics.map(_.timers.find(_.name == name).map(_.p50))) },
      timers.map { name => Row(name + ".p75", metrics.map(_.timers.find(_.name == name).map(_.p75))) },
      timers.map { name => Row(name + ".p95", metrics.map(_.timers.find(_.name == name).map(_.p95))) },
      timers.map { name => Row(name + ".p98", metrics.map(_.timers.find(_.name == name).map(_.p98))) },
      timers.map { name => Row(name + ".p99", metrics.map(_.timers.find(_.name == name).map(_.p99))) },
      timers.map { name => Row(name + ".p999", metrics.map(_.timers.find(_.name == name).map(_.p999))) },
      timers.map { name => Row(name + ".stddev", metrics.map(_.timers.find(_.name == name).map(_.stddev))) }
    ).flatten
  }

  def writeCSV(directory: File, csv: File, sep: String = ";") = {
    IO.using(new FileWriter(csv)) { writer =>
      toRows(directory).sortBy(_.name).foreach { row =>
        val values = row.values.map(_.map(_.toString.replace(".", ",")).getOrElse("n/a")).mkString(sep)
        writer.write(s"${row.name}$sep$values\n")
      }
    }
  }

  /**
    * Export CSV files from metric directories.
    * @param args the directories with metric files
    */
  def main(args: Array[String]) {
    args.map(new File(_)).filter(_.isDirectory).foreach { dir =>
      writeCSV(dir, new File(dir, "out.csv"))
    }
  }
}
