package mesosphere.marathon.metrics

import org.rogach.scallop.ScallopConf

trait MetricsConf extends ScallopConf {

  lazy val graphite = opt[String]("reporter_graphite",
    descr = "URL to graphite agent. e.g. tcp://1.2.3.4:1234?prefix=test&interval=10",
    noshort = true
  )

  lazy val dataDog = opt[String]("reporter_datadog",
    descr = "URL to dogstatsd agent. e.g. udp://1.2.3.4:1234?apiKey=foo&interval=10&expansions=min,max&tags=foo,bla",
    noshort = true
  )
}

