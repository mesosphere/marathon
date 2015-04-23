package mesosphere.marathon

import javax.inject.Provider

import com.codahale.metrics.MetricRegistry
import com.google.inject.AbstractModule
import com.google.inject.matcher.{ AbstractMatcher, Matchers }
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

  lazy val enableMetrics = opt[Boolean]("enable_metrics",
    descr = "Enable metric measurement of service method calls",
    default = Some(false),
    noshort = true)

  lazy val logLevel = opt[String]("logging_level",
    descr = "Set logging level to one of: off, fatal, error, warn, info, debug, trace, all",
    noshort = true
  )
}

class DebugModule(conf: DebugConf) extends AbstractModule {

  trait Behavior extends MethodInterceptor {
    def className(in: MethodInvocation): String = in.getThis.getClass.getName
  }

  /**
    * Measure processing time of each service method.
    */
  class MetricsBehavior(provider: Provider[MetricRegistry]) extends Behavior {
    override def invoke(in: MethodInvocation): AnyRef = {
      val timer = provider.get.timer(s"service.${className(in)}.${in.getMethod.getName}")
      val ctx = timer.time()
      try {
        in.proceed()
      }
      finally {
        ctx.stop()
      }
    }
  }

  /**
    * Add trace, whenever a service method is entered and finished.
    */
  class TracingBehavior extends Behavior {
    override def invoke(in: MethodInvocation): AnyRef = {
      val logger = Logger.getLogger(className(in))
      val method = s"""${className(in)}.${in.getMethod.getName}(${in.getArguments.mkString(", ")})"""
      logger.trace(s">>> $method")
      val result = in.proceed()
      logger.trace(s"<<< $method")
      result
    }
  }

  object MarathonMatcher extends AbstractMatcher[Class[_]] {
    override def matches(t: Class[_]): Boolean = t.getPackage.getName.startsWith("mesosphere")
  }

  override def configure(): Unit = {
    //set trace log level
    conf.logLevel.get.foreach(level => Logger.getRootLogger.setLevel(Level.toLevel(level.toUpperCase())))

    //add behaviors
    val registry = getProvider(classOf[MetricRegistry])
    val tracing = conf.debugTracing.get.filter(identity).map(_ => new TracingBehavior)
    val metrics = conf.enableMetrics.get.filter(identity).map(_ => new MetricsBehavior(registry))
    val behaviors = (tracing :: metrics :: Nil).flatten
    if (behaviors.nonEmpty) bindInterceptor(MarathonMatcher, Matchers.any(), behaviors: _*)
  }
}