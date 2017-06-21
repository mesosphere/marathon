package mesosphere.marathon

import java.net.URI

import ch.qos.logback.classic.{ AsyncAppender, Level, LoggerContext }
import ch.qos.logback.core.net.ssl.SSLConfiguration
import com.getsentry.raven.logback.SentryAppender
import com.google.inject.AbstractModule
import net.logstash.logback.appender._
import net.logstash.logback.composite.loggingevent.ArgumentsJsonProvider
import org.rogach.scallop.ScallopConf
import org.slf4j.{ Logger, LoggerFactory }

/**
  * Options related to debugging marathon.
  */
trait DebugConf extends ScallopConf {

  lazy val metrics = toggle(
    "metrics",
    descrYes =
      "(Deprecated) Ignored",
    descrNo =
      "(Deprecated) Ignored",
    default = Some(false),
    noshort = true,
    prefix = "disable_")

  lazy val logLevel = opt[String](
    "logging_level",
    descr = "Set logging level to one of: off, error, warn, info, debug, trace, all",
    noshort = true
  )

  lazy val logstash = opt[URI](
    "logstash",
    descr = "Logs destination URI in format (udp|tcp|ssl)://<host>:<port>",
    noshort = true
  )

  lazy val sentryUrl = opt[URI](
    "sentry",
    descr = "URI for sentry, e.g. https://<public>:<private>@sentryserver/",
    noshort = true
  )

  lazy val sentryTags = opt[String](
    "sentry_tags",
    descr = "Tags to post to sentry with, e.g: tag1:value1,tag2:value2"
  )
}

class DebugModule(conf: DebugConf) extends AbstractModule {
  override def configure(): Unit = {
    //set trace log levelN
    conf.logLevel.get.foreach { levelName =>
      val level = Level.toLevel(if ("fatal".equalsIgnoreCase(levelName)) "fatal" else levelName)
      LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) match {
        case l: ch.qos.logback.classic.Logger => l.setLevel(level)
        case _ => println(s"WARNING: Could not set log level to $level!")
      }
    }

    conf.logstash.get.foreach {
      configureLogstash
    }

    conf.sentryUrl.get.foreach {
      configureSentry(_, conf.sentryTags.get)
    }
  }

  private def configureSentry(uri: URI, tags: Option[String]): Unit = {
    LoggerFactory.getILoggerFactory match {
      case context: LoggerContext =>
        val appender = new SentryAppender()
        appender.setDsn(uri.toString)
        tags.foreach(appender.setTags)
        appender.setRelease(s"${BuildInfo.version}:${BuildInfo.buildref}")
        appender.setContext(context)
        appender.setName("sentry")
        val logger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        logger.addAppender(appender)
    }
  }

  private def configureLogstash(destination: URI): Unit = {
    LoggerFactory.getILoggerFactory match {
      case context: LoggerContext =>

        val encoder = new net.logstash.logback.encoder.LogstashEncoder()
        encoder.setContext(context)
        encoder.addProvider(new ArgumentsJsonProvider())
        encoder.start()

        val logstashAppender = destination.getScheme match {
          case "udp" =>
            val appender = new LogstashSocketAppender
            appender.setName("logstash_udp_appender")
            appender.setHost(destination.getHost)
            appender.setPort(destination.getPort)
            appender
          case "tcp" =>
            val appender = new LogstashTcpSocketAppender
            appender.setName("logstash_tcp_appender")
            appender.addDestination(s"${destination.getHost}:${destination.getPort}")
            appender.setEncoder(encoder)
            appender
          case "ssl" =>
            val appender = new LogstashTcpSocketAppender
            appender.setName("logstash_ssl_appender")
            appender.addDestination(s"${destination.getHost}:${destination.getPort}")
            appender.setEncoder(encoder)
            appender.setSsl(new SSLConfiguration)
            appender
          case scheme: String => throw new IllegalArgumentException(s"$scheme is not supported. Use tcp, udp or ssl")
        }

        logstashAppender.setContext(context)
        logstashAppender.start()

        val asyncAppender = new AsyncAppender()
        asyncAppender.setName("async_logstash_appender")
        asyncAppender.addAppender(logstashAppender)
        asyncAppender.setContext(context)
        asyncAppender.start()

        val logger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        logger.addAppender(asyncAppender)
    }
  }
}
