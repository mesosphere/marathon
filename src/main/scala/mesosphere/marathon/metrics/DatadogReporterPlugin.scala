package mesosphere.marathon.metrics

import java.net.{ InetAddress, InetSocketAddress, URI }
import java.util
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.metrics.MetricsReporterService.QueryParam
import org.apache.log4j.Logger
import org.coursera.metrics.datadog.DatadogReporter
import org.coursera.metrics.datadog.DatadogReporter.Expansion
import org.coursera.metrics.datadog.transport.{ HttpTransport, UdpTransport }
import mesosphere.marathon.plugin.plugin.PluginConfiguration
import mesosphere.marathon.plugin.metrics.MetricsReporter
import play.api.libs.json._

import scala.collection.JavaConverters._

//scalastyle:off magic.number
class DatadogReporterPlugin extends MetricsReporter.Factory with PluginConfiguration {

  private lazy val log = Logger.getLogger(getClass.getName)
  private var factory: Option[MetricsReporter.Factory] = None

  /**
    * Report to datadog.
    * UDP: udp://docker.agent:8125
    * Http: http://ignored?apiKey=XXX
    * with following parameters:
    *
    * - expansions: which metric data should be expanded. can be a list of:
    *   count,meanRate,1MinuteRate,5MinuteRate,15MinuteRate,min,mean,max,stddev,median,p75,p95,p98,p99,p999
    *   Default: all of the above
    * - interval: the interval to report to dataDogs
    *   Default: 10
    * - prefix: the prefix is prepended to all metric names
    *   Default: marathon_test
    * - tags: the tags to send with each metric
    *   Can be either simple value like `foo` or key value like `foo:bla`
    *   Default: empty
    *
    * Examples:
    *   http://datadog?apiKey=abc&prefix=marathon-test&tags=marathon&interval=10
    *   udp://localhost:8125?prefix=marathon-test&tags=marathon&interval=10
    */
  override def initialize(unused: Map[String, Any], pluginConfiguration: JsObject): Unit = {
    val dataDog = (pluginConfiguration \ "datadogURL").as[String]
    val url = new URI(dataDog)
    val params = Option(url.getQuery).getOrElse("").split("&").collect { case QueryParam(k, v) => k -> v }.toMap

    val transport = url.getScheme match {
      case "http" | "https" =>
        val transport = new HttpTransport.Builder()
        params.get("apiKey").map(transport.withApiKey)
        transport.build()
      case "udp" =>
        val transport = new UdpTransport.Builder()
        transport.withStatsdHost(url.getHost)
        if (url.getPort > 0) transport.withPort(url.getPort)
        transport.build()
      case unknown: String =>
        throw new WrongConfigurationException(s"Datadog: Unknown protocol $unknown")
    }

    val expansions = params.get("expansions").map(_.split(",").toSeq).getOrElse(Seq(
      "count", "meanRate", "1MinuteRate", "5MinuteRate", "15MinuteRate", "min", "mean", "max",
      "stddev", "median", "p75", "p95", "p98", "p99", "p999"))

    val interval = params.get("interval").map(_.toLong).getOrElse(10L)
    val prefix = params.getOrElse("prefix", "marathon_test")

    val tags = params.get("tags").map(_.split(",").toSeq).getOrElse(Seq.empty[String])

    factory = Some(MetricsReporter.Factory { registry =>
      val reporter = DatadogReporter
        .forRegistry(registry)
        .withTransport(transport)
        .withHost(InetAddress.getLocalHost.getHostName)
        .withPrefix(prefix)
        .withExpansions(util.EnumSet.copyOf(expansions.flatMap(e => Expansion.values().find(_.toString == e)).asJava))
        .withTags(tags.asJava)
        .build()

      log.info(s"Datadog reporter configured $reporter with $interval seconds interval (url: $dataDog)")
      reporter.start(interval, TimeUnit.SECONDS)
      Some(new MetricsReporter { override def stop(): Unit = reporter.stop })
    })
  }

  override def apply(registry: MetricRegistry): Option[MetricsReporter] = factory.flatMap(_(registry))
} // DatadogReporterPlugin
