package mesosphere.marathon.metrics

import java.net.{ InetSocketAddress, URI }
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.graphite.{ Graphite, GraphiteReporter }
import mesosphere.marathon.metrics.MetricsReporterService.QueryParam
import mesosphere.marathon.plugin.metrics.MetricsReporter
import mesosphere.marathon.plugin.plugin.PluginConfiguration
import org.apache.log4j.Logger
import play.api.libs.json._

//scalastyle:off magic.number
class GraphiteReporterPlugin extends MetricsReporter.Factory with PluginConfiguration {
  private lazy val log = Logger.getLogger(getClass.getName)
  private var factory: Option[MetricsReporter.Factory] = None

  /**
    * Report to graphite
    *
    * Specify host and port with following parameters:
    * - prefix: the prefix for all metrics
    *   Default: None
    * - interval: the interval to report to graphite in seconds
    *   Default: 10
    *
    * Example:
    *   tcp://localhost:2003?prefix=marathon-test&interval=10
    */
  override def initialize(unused: Map[String, Any], pluginConfiguration: JsObject): Unit = {
    val graphUrl = (pluginConfiguration \ "graphURL").as[String]
    val url = new URI(graphUrl)
    val params = Option(url.getQuery).getOrElse("").split("&").collect { case QueryParam(k, v) => k -> v }.toMap

    val graphite = new Graphite(new InetSocketAddress(url.getHost, url.getPort))
    factory = Some(MetricsReporter.Factory { registry =>
      val builder = GraphiteReporter.forRegistry(registry)
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
      params.get("prefix").map(builder.prefixedWith)
      val reporter = builder.build(graphite)
      val interval = params.get("interval").map(_.toLong).getOrElse(10L)

      log.info(s"Graphite reporter configured $reporter with $interval seconds interval (url: $graphUrl)")
      reporter.start(interval, TimeUnit.SECONDS)
      Some(new MetricsReporter { override def stop(): Unit = reporter.stop })
    })
  }
  override def apply(registry: MetricRegistry): Option[MetricsReporter] = factory.flatMap(_(registry))
}
