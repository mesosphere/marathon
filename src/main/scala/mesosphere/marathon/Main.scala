package mesosphere.marathon

import java.net.InetSocketAddress
import java.util

import com.google.inject.Module
import com.twitter.common.quantity.{ Amount, Time }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.chaos.App
import mesosphere.chaos.http.{ HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.event.EventModule
import mesosphere.marathon.event.http.HttpEventModule
import mesosphere.marathon.metrics.{ MetricsReporterModule, MetricsReporterService }
import scala.concurrent.{ Future, Await }
import org.apache.zookeeper.KeeperException
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

import scala.collection.JavaConverters._

class MarathonApp extends App {
  val log = LoggerFactory.getLogger(getClass.getName)

  lazy val zk: ZooKeeperClient = {
    require(
      conf.zooKeeperSessionTimeout() < Integer.MAX_VALUE,
      "ZooKeeper timeout too large!"
    )

    val client = new ZooKeeperLeaderElectionClient(
      Amount.of(conf.zooKeeperSessionTimeout().toInt, Time.MILLISECONDS),
      conf.zooKeeperHostAddresses.asJavaCollection
    )

    // Marathon can't do anything useful without a ZK connection
    // so we wait to proceed until one is available
    var connectedToZk = false

    while (!connectedToZk) {
      try {
        log.info("Connecting to ZooKeeper...")
        client.get
        connectedToZk = true
      }
      catch {
        case t: Throwable =>
          log.warn("Unable to connect to ZooKeeper, retrying...")
      }
    }
    client
  }

  def modules(): Seq[Module] = {
    Seq(
      new HttpModule(conf),
      new MetricsModule,
      new MetricsReporterModule(conf),
      new MarathonModule(conf, conf, zk),
      new MarathonRestModule,
      new EventModule(conf),
      new DebugModule(conf),
      new CoreGuiceModule
    ) ++ getEventsModule
  }

  def getEventsModule: Option[Module] = {
    conf.eventSubscriber.get.flatMap {
      case "http_callback" =>
        log.info("Using HttpCallbackEventSubscriber for event notification")
        Some(new HttpEventModule(conf))

      case _ =>
        log.info("Event notification disabled.")
        None
    }
  }

  override lazy val conf = new AllConf(args)

  override def initConf(): Unit = {
    super.initConf()
  }

  def runDefault(): Unit = {
    setConcurrentContextDefaults()

    log.info(s"Starting Marathon ${BuildInfo.version} with ${args.mkString(" ")}")

    run(
      classOf[HttpService],
      classOf[MarathonSchedulerService],
      classOf[MetricsReporterService]
    )
  }

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

  class ZooKeeperLeaderElectionClient(sessionTimeout: Amount[Integer, Time],
                                      zooKeeperServers: java.lang.Iterable[InetSocketAddress])
      extends ZooKeeperClient(sessionTimeout, zooKeeperServers) {

    override def shouldRetry(e: KeeperException): Boolean = {
      log.error("Got ZooKeeper exception", e)
      log.error("Committing suicide to avoid invalidating ZooKeeper state")

      val f = Future {
        // scalastyle:off magic.number
        Runtime.getRuntime.exit(9)
        // scalastyle:on
      }(scala.concurrent.ExecutionContext.global)

      try {
        Await.result(f, 5.seconds)
      }
      catch {
        case _: Throwable =>
          log.error("Finalization failed, killing JVM.")
          // scalastyle:off magic.number
          Runtime.getRuntime.halt(1)
        // scalastyle:on
      }

      false
    }
  }
}

object Main extends MarathonApp {
  runDefault()
}
