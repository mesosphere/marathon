package mesosphere.marathon

import java.lang.Thread.UncaughtExceptionHandler

import com.google.common.util.concurrent.ServiceManager
import com.google.inject.{ Guice, Module }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.chaos.http.{ HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.base.toRichRuntime
import mesosphere.marathon.metrics.{ MetricsReporterModule, MetricsReporterService }
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.LibMesos
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.ExecutionContext

class MarathonApp(args: Seq[String]) extends AutoCloseable with StrictLogging {
  private var running = false
  private val log = LoggerFactory.getLogger(getClass.getName)

  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()
  Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler {
    override def uncaughtException(thread: Thread, throwable: Throwable): Unit = {
      logger.error(s"Terminating ${conf.httpPort()} due to uncaught exception in thread ${thread.getName}:${thread.getId}", throwable)
      Runtime.getRuntime.asyncExit()(ExecutionContext.global)
    }
  })

  protected def modules: Seq[Module] = {
    Seq(
      new HttpModule(conf),
      new MetricsModule,
      new MetricsReporterModule(conf),
      new MarathonModule(conf, conf),
      new MarathonRestModule,
      new DebugModule(conf),
      new CoreGuiceModule
    )
  }
  private var serviceManager: Option[ServiceManager] = None

  private val EnvPrefix = "MARATHON_CMD_"
  private lazy val envArgs: Array[String] = {
    sys.env.withFilter(_._1.startsWith(EnvPrefix)).flatMap {
      case (key, value) =>
        val argKey = s"--${key.replaceFirst(EnvPrefix, "").toLowerCase.trim}"
        if (value.trim.length > 0) Seq(argKey, value) else Seq(argKey)
    }(collection.breakOut)
  }

  val conf = {
    new AllConf(args ++ envArgs)
  }

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

    val injector = Guice.createInjector(modules)
    val services = Seq(
      classOf[HttpService],
      classOf[MarathonSchedulerService],
      classOf[MetricsReporterService]).map(injector.getInstance(_))
    serviceManager = Some(new ServiceManager(services))

    sys.addShutdownHook(shutdownAndWait())

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
    * ExecutionContext.global is an ExecutionContext backed by a ForkJoinPool. It should be sufficient for most
    * situations but requires some care. A ForkJoinPool manages a limited amount of threads (the maximum amount of
    * thread being referred to as parallelism level). The number of concurrently blocking computations can exceed the
    * parallelism level only if each blocking call is wrapped inside a blocking call (more on that below). Otherwise,
    * there is a risk that the thread pool in the global execution context is starved, and no computation can proceed.
    *
    * By default the ExecutionContext.global sets the parallelism level of its underlying fork-join pool to the amount
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
