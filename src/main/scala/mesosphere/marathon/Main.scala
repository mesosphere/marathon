package mesosphere.marathon

import akka.actor.ActorSystem
import com.google.common.util.concurrent.ServiceManager
import com.google.inject.{Guice, Module}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.LeaderProxyFilterModule
import org.eclipse.jetty.servlets.EventSourceServlet

import scala.concurrent.ExecutionContext.Implicits.global
import mesosphere.marathon.api.HttpModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.base.{CrashStrategy, toRichRuntime}
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.LibMesos
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.Await
import scala.concurrent.duration._

class MarathonApp(args: Seq[String]) extends AutoCloseable with StrictLogging {
  private var running = false

  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()
  Thread.setDefaultUncaughtExceptionHandler((thread: Thread, throwable: Throwable) => {
    logger.error(s"Terminating due to uncaught exception in thread ${thread.getName}:${thread.getId}", throwable)
    Runtime.getRuntime.asyncExit(CrashStrategy.UncaughtException.code)
  })

  if (LibMesos.isCompatible) {
    logger.info(s"Successfully loaded libmesos: version ${LibMesos.version}")
  } else {
    logger.error(s"Failed to load libmesos: ${LibMesos.version}")
    System.exit(CrashStrategy.IncompatibleLibMesos.code)
  }

  val cliConf = new AllConf(args)
  val actorSystem = ActorSystem("marathon")

  val marathonRestModule = new MarathonRestModule()
  val leaderProxyFilterModule = new LeaderProxyFilterModule()

  protected def modules: Seq[Module] =
    Seq(
      marathonRestModule,
      leaderProxyFilterModule,
      new MarathonModule(cliConf, cliConf, actorSystem),
      new DebugModule(cliConf),
      new CoreGuiceModule(cliConf))

  private var serviceManager: Option[ServiceManager] = None

  val injector = Guice.createInjector(modules.asJava)
  val httpModule = new HttpModule(conf = cliConf, injector.getInstance(classOf[MetricsModule]))
  val services = Seq(
    httpModule.marathonHttpService,
    injector.getInstance(classOf[MarathonSchedulerService]))

  def start(): Unit = if (!running) {
    running = true
    setConcurrentContextDefaults()

    logger.info(s"Starting Marathon ${BuildInfo.version}/${BuildInfo.buildref} with ${args.mkString(" ")}")

    api.HttpBindings.apply(
      httpModule.servletContextHandler,
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

      logger.info("Shutting down actor system {}", actorSystem)
      Await.result(actorSystem.terminate(), 10.seconds)
    }

    serviceManager.foreach(_.startAsync())

    try {
      serviceManager.foreach(_.awaitHealthy())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to start all services. Services by state: ${serviceManager.map(_.servicesByState()).getOrElse("[]")}", e)
        shutdownAndWait()
        throw e
    }

    logger.info("All services up and running.")
  }

  def shutdown(): Unit = if (running) {
    running = false
    logger.info("Shutting down services")
    serviceManager.foreach(_.stopAsync())
  }

  def shutdownAndWait(): Unit = {
    serviceManager.foreach { serviceManager =>
      shutdown()
      logger.info("Waiting for services to shut down")
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
  /**
    * Given environment variables starting with MARATHON_, convert to a series of arguments.
    *
    * If environment variable specifies an empty string, treat to boolean flag (no argument)
    *
    * @returns A list of args intended to be arg-parsed
    */
  def envToArgs(env: Map[String, String]): Seq[String] = {
    env.flatMap {
      case (k, v) if k.startsWith("MARATHON_APP_") =>
        /* Marathon sets passes several environment variables, prefixed with MARATHON_APP_, to Marathon instances. We
         * need to explicitly ignore these and not treat them as parameters in the case of Marathon launching other
         * instances of Marathon */
        Nil
      case (k, v) if k.startsWith("MARATHON_") =>
        val argName = s"--${k.drop(9).toLowerCase}"
        if (v.isEmpty)
          Seq(argName)
        else
          Seq(argName, v)
      case _ => Nil
    }(collection.breakOut)
  }

  def main(args: Array[String]): Unit = {
    val app = new MarathonApp(envToArgs(sys.env) ++ args.toVector)
    app.start()
  }
}
