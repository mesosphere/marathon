package mesosphere.marathon

import javax.inject.Provider

import ch.qos.logback.classic.Level
import com.google.inject.AbstractModule
import com.google.inject.matcher.{ AbstractMatcher, Matchers }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.aopalliance.intercept.{ MethodInterceptor, MethodInvocation }
import org.rogach.scallop.ScallopConf
import org.slf4j.{ Logger, LoggerFactory }

/**
  * Options related to debugging marathon.
  */
trait DebugConf extends ScallopConf {

  lazy val debugTracing = toggle("tracing",
    descrYes = "Enable trace logging of service method calls.",
    descrNo = "(Default) Disable trace logging of service method calls.",
    default = Some(false),
    noshort = true,
    prefix = "disable_")

  lazy val deprecatedDebugTracing = opt[Boolean]("enable_tracing", hidden = true)

  mutuallyExclusive(debugTracing, deprecatedDebugTracing)
  lazy val enableDebugTracing = debugTracing() || deprecatedDebugTracing()

  lazy val metrics = toggle("metrics",
    descrYes =
      "(Default) Expose the execution time of service method calls using code instrumentation" +
        " via the metrics endpoint (/metrics). This might noticeably degrade performance" +
        " but can help finding performance problems.",
    descrNo =
      "Disable exposing execution time of service method calls using code instrumentation" +
        " via the metrics endpoing (/metrics). " +
        "This does not turn off reporting of other metrics.",
    default = Some(true),
    noshort = true,
    prefix = "disable_")

  lazy val deprecatedEnableMetrics = opt[Boolean]("enable_metrics", default = Some(false), hidden = true)

  mutuallyExclusive(metrics, deprecatedEnableMetrics)

  lazy val logLevel = opt[String]("logging_level",
    descr = "Set logging level to one of: off, error, warn, info, debug, trace, all",
    noshort = true
  )
}

class DebugModule(conf: DebugConf) extends AbstractModule {
  /**
    * Measure processing time of each service method.
    */
  class MetricsBehavior(metricsProvider: Provider[Metrics]) extends MethodInterceptor {
    override def invoke(in: MethodInvocation): AnyRef = {
      val metrics: Metrics = metricsProvider.get

      metrics.timed(metrics.name(MetricPrefixes.SERVICE, in)) {
        in.proceed
      }
    }
  }

  /**
    * Add trace, whenever a service method is entered and finished.
    */
  class TracingBehavior(metrics: Provider[Metrics]) extends MethodInterceptor {
    override def invoke(in: MethodInvocation): AnyRef = {
      val className = metrics.get.className(in.getThis.getClass)
      val logger = LoggerFactory.getLogger(className)
      val method = s"""$className.${in.getMethod.getName}(${in.getArguments.mkString(", ")})"""
      logger.trace(s">>> $method")
      val result = in.proceed()
      logger.trace(s"<<< $method")
      result
    }
  }

  object MarathonMatcher extends AbstractMatcher[Class[_]] {
    override def matches(t: Class[_]): Boolean = {
      // Don't instrument the Metrics class, in order to avoid an infinite recursion
      t.getPackage.getName.startsWith("mesosphere") && t != classOf[Metrics]
    }
  }

  override def configure(): Unit = {
    //set trace log level
    conf.logLevel.get.foreach { levelName =>
      val level = Level.toLevel(if ("fatal".equalsIgnoreCase(levelName)) "fatal" else levelName)
      val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
      rootLogger.setLevel(level)
    }

    //add behaviors
    val metricsProvider = getProvider(classOf[Metrics])

    val tracingBehavior = if (conf.enableDebugTracing) Some(new TracingBehavior(metricsProvider)) else None
    val metricsBehavior = conf.metrics.get.filter(identity).map(_ => new MetricsBehavior(metricsProvider))

    val behaviors = (tracingBehavior :: metricsBehavior :: Nil).flatten
    if (behaviors.nonEmpty) bindInterceptor(MarathonMatcher, Matchers.any(), behaviors: _*)
  }
}
