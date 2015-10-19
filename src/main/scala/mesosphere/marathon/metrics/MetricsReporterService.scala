package mesosphere.marathon.metrics

import java.net.{ InetAddress, InetSocketAddress, URI }
import java.util
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.graphite.{ Graphite, GraphiteReporter }
import com.google.common.util.concurrent.AbstractIdleService
import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.metrics.MetricsReporterService.QueryParam
import org.apache.log4j.Logger
import org.coursera.metrics.datadog.DatadogReporter
import org.coursera.metrics.datadog.DatadogReporter.Expansion
import org.coursera.metrics.datadog.transport.{ HttpTransport, UdpTransport }

import scala.collection.JavaConverters._

object MetricsReporterService {

  object QueryParam {
    def unapply(str: String): Option[(String, String)] = str.split("=") match {
      case Array(key: String, value: String) => Some(key -> value)
      case _                                 => None
    }
  }
}

//scalastyle:off magic.number
class MetricsReporterService @Inject() (config: MetricsReporterConf, registry: MetricRegistry)
    extends AbstractIdleService {

  private val log = Logger.getLogger(getClass.getName)

  private[this] var graphite: Option[GraphiteReporter] = None

  private[this] var datadog: Option[DatadogReporter] = None

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
  private[this] def startGraphiteReporter(graphUrl: String): GraphiteReporter = {
    val url = new URI(graphUrl)
    val params = Option(url.getQuery).getOrElse("").split("&").collect { case QueryParam(k, v) => k -> v }.toMap

    val graphite = new Graphite(new InetSocketAddress(url.getHost, url.getPort))
    val builder = GraphiteReporter.forRegistry(registry)
      .convertRatesTo(TimeUnit.SECONDS)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
    params.get("prefix").map(builder.prefixedWith)
    val reporter = builder.build(graphite)
    val interval = params.get("interval").map(_.toLong).getOrElse(10L)

    log.info(s"Graphite reporter configured $reporter with $interval seconds interval (url: $graphUrl)")
    reporter.start(interval, TimeUnit.SECONDS)
    reporter
  }

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
  private[this] def startDatadog(dataDog: String): DatadogReporter = {
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
    reporter
  }

  def startUp() {
    this.graphite = config.graphite.get.map(startGraphiteReporter)
    this.datadog = config.dataDog.get.map(startDatadog)
  }

  def shutDown() {
    graphite.foreach(_.stop)
    datadog.foreach(_.stop)
  }
}
