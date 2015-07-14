package mesosphere.marathon

import javax.inject.Provider

import com.google.inject.AbstractModule
import com.google.inject.matcher.{ AbstractMatcher, Matchers }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.aopalliance.intercept.{ MethodInterceptor, MethodInvocation }
import org.apache.log4j.{ Level, Logger }
import org.rogach.scallop.ScallopConf

/**
  * Options related to debugging marathon.
  * All options should be optional and turned off by default.
  */
trait DebugConf extends ScallopConf {

  lazy val debugTracing = opt[Boolean]("enable_tracing",
    descr = "Enable trace logging of service method calls",
    default = Some(false),
    noshort = true)

  @deprecated("Metrics are enabled by default. See disableMetrics.", "Since version 0.10.0")
  lazy val enableMetrics = opt[Boolean]("enable_metrics",
    descr = "Enable metric measurement of service method calls",
    hidden = true,
    default = Some(true),
    noshort = true)

  lazy val disableMetrics = opt[Boolean]("disable_metrics",
    descr = "Disable metric measurement of service method calls",
    default = Some(false),
    noshort = true)

  lazy val logLevel = opt[String]("logging_level",
    descr = "Set logging level to one of: off, fatal, error, warn, info, debug, trace, all",
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
      val logger = Logger.getLogger(className)
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
    conf.logLevel.get.foreach(level => Logger.getRootLogger.setLevel(Level.toLevel(level.toUpperCase)))

    //add behaviors
    val metricsProvider = getProvider(classOf[Metrics])

    val tracingBehavior = conf.debugTracing.get.filter(identity).map(_ => new TracingBehavior(metricsProvider))
    val metricsBehavior = conf.disableMetrics.get.filterNot(identity).map(_ => new MetricsBehavior(metricsProvider))

    val behaviors = (tracingBehavior :: metricsBehavior :: Nil).flatten
    if (behaviors.nonEmpty) bindInterceptor(MarathonMatcher, Matchers.any(), behaviors: _*)
  }
}
