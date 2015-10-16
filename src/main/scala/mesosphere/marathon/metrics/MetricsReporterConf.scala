package mesosphere.marathon.metrics

import org.rogach.scallop.ScallopConf

trait MetricsReporterConf extends ScallopConf {

  lazy val graphite = opt[String]("reporter_graphite",
    descr = "URL to graphite agent. e.g. tcp://localhost:2003?prefix=marathon-test&interval=10",
    noshort = true
  )

  lazy val dataDog = opt[String]("reporter_datadog",
    descr = "URL to dogstatsd agent. e.g. udp://localhost:8125?prefix=marathon-test&tags=marathon&interval=10",
    noshort = true
  )
}

