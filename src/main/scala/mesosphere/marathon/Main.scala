package mesosphere.marathon

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import com.google.common.util.concurrent.ServiceManager
import com.google.inject.{ Guice, Module }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.{ LeaderProxyFilterModule, SystemResource }
import org.eclipse.jetty.servlets.EventSourceServlet
import scala.concurrent.ExecutionContext.Implicits.global
import kamon.Kamon
import mesosphere.marathon.api.HttpModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.base.toRichRuntime
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.LibMesos
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.Await
import scala.concurrent.duration._

class MarathonApp(args: Seq[String]) extends AutoCloseable with StrictLogging {
  private var running = false
  private val log = LoggerFactory.getLogger(getClass.getName)

  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()
  Thread.setDefaultUncaughtExceptionHandler((thread: Thread, throwable: Throwable) => {
    logger.error(s"Terminating due to uncaught exception in thread ${thread.getName}:${thread.getId}", throwable)
    Runtime.getRuntime.asyncExit()
  })

  private val EnvPrefix = "MARATHON_CMD_"
  private val envArgs: Array[String] = {
    sys.env.withFilter(_._1.startsWith(EnvPrefix)).flatMap {
      case (key, value) =>
        val argKey = s"--${key.replaceFirst(EnvPrefix, "").toLowerCase.trim}"
        if (value.trim.length > 0) Seq(argKey, value) else Seq(argKey)
    }(collection.breakOut)
  }

  val cliConf: AllConf = {
    new AllConf(args ++ envArgs)
  }

  val config: Config = {
    // eventually we will need a more robust way of going from Scallop -> Config.
    val overrides = {

      //
      // Create Kamon configuration spec for the `kamon.datadog` plugin
      //
      val datadog = cliConf.dataDog.get.map { urlStr =>
        val url = Uri(urlStr)
        val params = url.query()

        (List(
          Some("kamon.datadog.hostname" -> url.authority.host),
          Some("kamon.datadog.port" -> (if (url.authority.port == 0) 8125 else url.authority.port)),
          Some("kamon.datadog.flush-interval" -> s"${params.collectFirst { case ("interval", iv) => iv.toInt }.getOrElse(10)} seconds")
        ) ++ params.map {
            case ("prefix", value) => Some("kamon.datadog.application-name" -> value)
            case ("tags", value) => Some("kamon.datadog.global-tags" -> value)
            case ("max-packet-size", value) => Some("kamon.datadog.max-packet-size" -> value)
            case ("api-key", value) => Some("kamon.datadog.http.api-key" -> value)
            case ("interval", _) => None // (This case used only to account this as 'known' parameter)
            case (name, _) => {
              logger.warn(s"Datadog reporter parameter `${name}` is unknown and ignored")
              None
            }
          }).flatten.toMap
      }

      //
      // Create Kamon configuration spec for the `kamon.statsd` plugin
      //
      val statsd = cliConf.graphite.get.map { urlStr =>
        val url = Uri(urlStr)
        val params = url.query()

        if (url.scheme.toLowerCase() != "udp") {
          logger.warn(s"Graphite reporter protocol ${url.scheme} is not supported; using UDP")
        }

        (List(
          Some("kamon.statsd.hostname" -> url.authority.host),
          Some("kamon.statsd.port" -> (if (url.authority.port == 0) 8125 else url.authority.port)),
          Some("kamon.statsd.flush-interval" -> s"${params.collectFirst { case ("interval", iv) => iv.toInt }.getOrElse(10)} seconds")
        ) ++ params.map {
            case ("prefix", value) => Some("kamon.statsd.simple-metric-key-generator.application" -> value)
            case ("hostname", value) => Some("kamon.statsd.simple-metric-key-generator.hostname-override" -> value)
            case ("interval", _) => None // (This case used only to account this as 'known' parameter)
            case (name, _) => {
              logger.warn(s"Statsd reporter parameter `${name}` is unknown and ignored")
              None
            }
          }).flatten.toMap
      }

      // Stringify the map
      val values = datadog.getOrElse(Map()) ++ statsd.getOrElse(Map())
      ConfigFactory.parseString(values.map(kv => s"${kv._1}: ${kv._2}").mkString("\n"))
    }

    overrides.withFallback(ConfigFactory.load())
  }
  Kamon.start(config)

  val actorSystem = ActorSystem("marathon")

  val httpModule = new HttpModule(conf = cliConf)
  val metricModule = new MetricsModule()
  metricModule.register(
    servletContextHandler = httpModule.handler,
    handlerCollection = httpModule.handlerCollection,
    requestLogHandler = httpModule.requestLogHandler)


  val marathonRestModule = new MarathonRestModule()
  val leaderProxyFilterModule = new LeaderProxyFilterModule()

  protected def modules: Seq[Module] =
    Seq(
      marathonRestModule,
      leaderProxyFilterModule,
      new MarathonModule(cliConf, cliConf, actorSystem),
      new DebugModule(cliConf),
      new CoreGuiceModule(config))

  private var serviceManager: Option[ServiceManager] = None

  def start(): Unit = if (!running) {
    running = true
    setConcurrentContextDefaults()

    log.info(s"Starting Marathon ${BuildInfo.version}/${BuildInfo.buildref} with ${args.mkString(" ")}")

    if (LibMesos.isCompatible) {
      log.info(s"Successfully loaded libmesos: version ${LibMesos.version}")
    } else {
      log.error(s"Failed to load libmesos: ${LibMesos.version}")
      System.exit(1)
    }

    val injector = Guice.createInjector(modules.asJava)
    Metrics.start(injector.getInstance(classOf[ActorSystem]), cliConf)
    val services = Seq(
      httpModule.marathonHttpService,
      injector.getInstance(classOf[MarathonSchedulerService]))

    api.HttpBindings.apply(
      httpModule.handler,
      systemResource = injector.getInstance(classOf[SystemResource]),
      rootApplication = injector.getInstance(classOf[api.RootApplication]),
      leaderProxyFilter = injector.getInstance(classOf[api.LeaderProxyFilter]),
      limitConcurrentRequestsFilter = injector.getInstance(classOf[api.LimitConcurrentRequestsFilter]),
      corsFilter = injector.getInstance(classOf[api.CORSFilter]),
      cacheDisablingFilter = injector.getInstance(classOf[api.CacheDisablingFilter]),
      eventSourceServlet = injector.getInstance(classOf[EventSourceServlet]),
      webJarServlet = injector.getInstance(classOf[api.WebJarServlet]),
      publicServlet = injector.getInstance(classOf[api.PublicServlet]))

    serviceManager = Some(new ServiceManager(services.asJava))

    sys.addShutdownHook {
      shutdownAndWait()

      log.info("Shutting down actor system {}", actorSystem)
      Await.result(actorSystem.terminate(), 10.seconds)
    }

    serviceManager.foreach(_.startAsync())

    try {
      serviceManager.foreach(_.awaitHealthy())
    } catch {
      case e: Exception =>
        log.error(s"Failed to start all services. Services by state: ${serviceManager.map(_.servicesByState()).getOrElse("[]")}", e)
        shutdownAndWait()
        throw e
    }

    log.info("All services up and running.")
  }

  def shutdown(): Unit = if (running) {
    running = false
    log.info("Shutting down services")
    serviceManager.foreach(_.stopAsync())
  }

  def shutdownAndWait(): Unit = {
    serviceManager.foreach { serviceManager =>
      shutdown()
      log.info("Waiting for services to shut down")
      serviceManager.awaitStopped()
    }
  }

  override def close(): Unit = shutdownAndWait()

  /**
    * Make sure that we have more than one thread -- otherwise some unmarked blocking operations might cause trouble.
    *
    * See
    * [The Global Execution
    *  Context](http://docs.scala-lang.org/overviews/core/futures.html#the-global-execution-context)
    * in the scala documentation.
    *
    * Here is the relevant excerpt in case the link gets broken:
    *
    * # The Global Execution Context
    *
    * ExecutionContext.Implicits.global is an ExecutionContext backed by a ForkJoinPool. It should be sufficient for most
    * situations but requires some care. A ForkJoinPool manages a limited amount of threads (the maximum amount of
    * thread being referred to as parallelism level). The number of concurrently blocking computations can exceed the
    * parallelism level only if each blocking call is wrapped inside a blocking call (more on that below). Otherwise,
    * there is a risk that the thread pool in the global execution context is starved, and no computation can proceed.
    *
    * By default the ExecutionContext.Implicits.global sets the parallelism level of its underlying fork-join pool to the amount
    * of available processors (Runtime.availableProcessors). This configuration can be overriden by setting one
    * (or more) of the following VM attributes:
    *
    * scala.concurrent.context.minThreads - defaults to Runtime.availableProcessors
    * scala.concurrent.context.numThreads - can be a number or a multiplier (N) in the form ‘xN’ ;
    *                                       defaults to Runtime.availableProcessors
    * scala.concurrent.context.maxThreads - defaults to Runtime.availableProcessors
    *
    * The parallelism level will be set to numThreads as long as it remains within [minThreads; maxThreads].
    *
    * As stated above the ForkJoinPool can increase the amount of threads beyond its parallelismLevel in the
    * presence of blocking computation.
    */
  private[this] def setConcurrentContextDefaults(): Unit = {
    def setIfNotDefined(property: String, value: String): Unit = {
      if (!sys.props.contains(property)) {
        sys.props += property -> value
      }
    }

    setIfNotDefined("scala.concurrent.context.minThreads", "5")
    setIfNotDefined("scala.concurrent.context.numThreads", "x2")
    setIfNotDefined("scala.concurrent.context.maxThreads", "64")
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val app = new MarathonApp(args.toVector)
    app.start()
  }
}
