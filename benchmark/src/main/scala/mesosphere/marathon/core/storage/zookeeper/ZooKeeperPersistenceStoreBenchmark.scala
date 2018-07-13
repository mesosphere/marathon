package mesosphere.marathon
package core.storage.zookeeper

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.BoundedExponentialBackoffRetry
import org.apache.curator.x.async.api.CreateOption
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Random

/**
  * To run e.g. [[ZooKeeperPersistenceStoreBenchmark.create()]] benchmark execute from the console:
  * $ sbt "benchmark/clean" "benchmark/jmh:run  .*ZooKeeperPersistenceStoreBenchmark.create"
  *
  * Note that this benchmark expects a Zookeeper instance running locally at localhost:2181 to produce relevant
  * numbers (as opposed to a Zookeeper test server running in the same JVM).
  *
  * Benchmarking reading/deleting/updating operations requires a ZK pre-populated with nodes with different payload
  * sizes. To simplify testing, [[ZooKeeperPersistenceStoreBenchmark.main()]] method could be run manually prior to
  * the test. Turns out that doing this in the [[org.openjdk.jmh.annotations.Setup]] is not quite trivial since
  * benchmark iterations are forked and run in different threads and for the purposes of the benchmark one need
  * consistently named nodes.
  *
  * Running multiple benchmark tests at the same time is not sensible e.g. running a [[ZooKeeperPersistenceStoreBenchmark.read()]]
  * after [[ZooKeeperPersistenceStoreBenchmark.delete()]] will read non-existing nodes. To run individual benchmarks call
  * $ .*ZooKeeperPersistenceStoreBenchmark.update
  *
  * from the console. Pre-populate Zookeeper with data if needed (e.g. for update/read/delete benchmarks)
  *
  * Note also that node data compression is disabled for the purposes of this benchmark
  */
@State(Scope.Benchmark)
object ZooKeeperPersistenceStoreBenchmark extends StrictLogging {

  implicit lazy val system: ActorSystem = ActorSystem()
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()
  implicit lazy val ec: ExecutionContext = system.dispatcher

  object Conf extends ZookeeperConf
  Conf.verify()

  val curator: CuratorFramework = CuratorFrameworkFactory.newClient(
    Conf.zooKeeperUrl().hostsString,
    Conf.zooKeeperSessionTimeout().toInt,
    Conf.zooKeeperConnectionTimeout().toInt,
    new BoundedExponentialBackoffRetry(Conf.zooKeeperOperationBaseRetrySleepMs(), Conf.zooKeeperTimeout().toInt, Conf.zooKeeperOperationMaxRetries())
  )
  curator.start()

  lazy val settings: AsyncCuratorBuilderSettings = new AsyncCuratorBuilderSettings(createOptions = Set(CreateOption.createParentsIfNeeded), compressedData = false)
  lazy val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(curator, settings)
  lazy val metrics: Metrics = DummyMetrics
  lazy val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(metrics, factory, parallelism = 16)

  // An map of node size to number of nodes of that size. Used for read, update and delete benchmarks. Note that
  // different number of nodes is used depending on the node data size e.g. creating 10K nodes with 1Mb data is not
  // practical since it will result in 10Gb of data.
  type NodeSize = Int
  type NodeNumber = Int
  val params = Map[NodeSize, NodeNumber]((10, 10000), (100, 10000), (1024, 10000), (10240, 10000), (102400, 1000))

  /**
    * Helper method to pre-populate Zookeeper with data. By default nodes are created with path:
    * /tests/{size}/node{index} e.g. "/tests/100/node123" for a 123th node with data size 100b.
    *
    * @param args
    */
  def main(args: Array[String]): Unit = {
    def populate(size: Int, num: Int) = {
      Source(1 to num)
        .map(i => Node(s"/tests/$size/node$i", ByteString(Random.alphanumeric.take(size).mkString)))
        .via(store.createFlow)
        .runWith(Sink.ignore)
    }

    Await.result(
      Source.fromIterator(() => params.iterator)
        .map{ p => logger.info(s"Creating ${p._2} nodes with ${p._1}b data"); p }
        .map{ case (size, num) => populate(size, num) }
        .mapAsync(1)(identity)
        .runWith(Sink.ignore),
      Duration.Inf)

    logger.info("Zookeeper successfully populated with data")
    system.terminate()
  }
}

@Fork(1)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Warmup(iterations = 15, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
class ZooKeeperPersistenceStoreBenchmark {
  import ZooKeeperPersistenceStoreBenchmark._

  /** Node data size */
  @Param(value = Array("10", "100", "1024", "10240", "102400"))
  var size: Int = _

  /** Number of nodes per operation (Source size) */
  @Param(value = Array("1", "10", "100", "1000"))
  var num: Int = _

  def randomPath(prefix: String = "", size: Int = 10): String =
    s"$prefix/${Random.alphanumeric.take(size).mkString}"

  @Benchmark
  def create(hole: Blackhole) = {
    // For created nodes not to collide with each other we use random node names generated by `randomPath`
    val res = Await.result(
      Source(1 to num)
        .map(_ => Node(randomPath("/tests"), ByteString(Random.alphanumeric.take(size).mkString)))
        .via(store.createFlow)
        .runWith(Sink.ignore), Duration.Inf)

    hole.consume(res)
  }

  @Benchmark
  def read(hole: Blackhole) = {
    def paths = Source(1 to num)
      .map(_ => s"/tests/$size/node${Random.nextInt(params(size)) + 1}")

    val res = Await.result(
      paths
        .via(store.readFlow)
        .runWith(Sink.ignore), Duration.Inf)
    hole.consume(res)
  }

  @Benchmark
  def delete(hole: Blackhole) = {
    def paths = Source(1 to num)
      .map(_ => s"/tests/$size/node${Random.nextInt(params(size)) + 1}")

    val res = Await.result(
      paths
        .via(store.deleteFlow)
        .runWith(Sink.ignore), Duration.Inf)
    hole.consume(res)
  }

  @Benchmark
  def update(hole: Blackhole) = {
    val res = Await.result(
      Source(1 to num)
        .map(_ => Node(s"/tests/$size/node${Random.nextInt(params(size)) + 1}", ByteString(Random.alphanumeric.take(size).mkString)))
        .via(store.updateFlow)
        .runWith(Sink.ignore), Duration.Inf)
    hole.consume(res)
  }

  @TearDown(Level.Trial)
  def close(): Unit = {
    curator.close()
    Await.result(system.terminate(), Duration.Inf)
  }
}