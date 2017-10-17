package mesosphere.marathon

import java.lang.Thread.UncaughtExceptionHandler
import java.net.URI

import akka.actor.ActorSystem
import com.google.common.util.concurrent.ServiceManager
import com.google.inject.{ Guice, Module }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import mesosphere.chaos.http.HttpModule
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.{ MarathonHttpService, MarathonRestModule }
import mesosphere.marathon.api.akkahttp.AkkaHttpModule
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.async.ExecutionContexts
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
  Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler {
    override def uncaughtException(thread: Thread, throwable: Throwable): Unit = {
      logger.error(s"Terminating ${cliConf.httpPort()} due to uncaught exception in thread ${thread.getName}:${thread.getId}", throwable)
      Runtime.getRuntime.asyncExit()(ExecutionContexts.global)
    }
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
      val datadog = cliConf.dataDog.get.map { urlStr =>
        val url = new URI(urlStr)
        s"""
      |kamon.datadog {
      |hostname: ${url.getHost}
      |port: ${if (url.getPort == -1) 8125 else url.getPort}
      |}
        """.stripMargin
      }
      val statsd = cliConf.graphite.get.map { urlStr =>
        val url = new URI(urlStr)
        s"""
           |kamon.statsd {
           |  hostname: ${url.getHost}
           |  port: ${if (url.getPort == -1) 8125 else url.getPort}
           |}
         """.stripMargin
      }

      ConfigFactory.parseString(s"${datadog.getOrElse("")}\n${statsd.getOrElse("")}")
    }

    overrides.withFallback(ConfigFactory.load())
  }
  Kamon.start(config)

  val actorSystem = ActorSystem("marathon")

  protected def modules: Seq[Module] = {
    val httpModules = if (cliConf.featureAkkaHttpServiceBackend())
      Seq(new AkkaHttpModule(cliConf))
    else
      Seq(new HttpModule(cliConf), new MarathonRestModule)

    httpModules ++
      Seq(
        new MetricsModule,
        new MarathonModule(cliConf, cliConf, actorSystem),
        new DebugModule(cliConf),
        new CoreGuiceModule(config)
      )
  }
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
    Metrics.start(injector.getInstance(classOf[ActorSystem]))
    val services = Seq(
      injector.getInstance(classOf[MarathonHttpService]),
      injector.getInstance(classOf[MarathonSchedulerService]))
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
    * ExecutionContexts.global is an ExecutionContext backed by a ForkJoinPool. It should be sufficient for most
    * situations but requires some care. A ForkJoinPool manages a limited amount of threads (the maximum amount of
    * thread being referred to as parallelism level). The number of concurrently blocking computations can exceed the
    * parallelism level only if each blocking call is wrapped inside a blocking call (more on that below). Otherwise,
    * there is a risk that the thread pool in the global execution context is starved, and no computation can proceed.
    *
    * By default the ExecutionContexts.global sets the parallelism level of its underlying fork-join pool to the amount
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
