package mesosphere.marathon
package core.storage.repository

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.repository.TemplateRepositoryLike.Versioned
import mesosphere.marathon.core.storage.repository.impl.TemplateRepository
import mesosphere.marathon.core.storage.zookeeper.{AsyncCuratorBuilderFactory, AsyncCuratorBuilderSettings, ZooKeeperPersistenceStore}
import mesosphere.marathon.state.{AppDefinition, PathId}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.BoundedExponentialBackoffRetry
import org.apache.curator.x.async.api.CreateOption
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Random

/**
  * To run e.g. [[TemplateRepositoryBenchmark#create()]] benchmark execute from the console:
  * $ sbt "benchmark/clean" "benchmark/jmh:run  mesosphere.marathon.core.storage.repository.TemplateRepositoryBenchmark.create"
  */
@State(Scope.Benchmark)
object TemplateRepositoryBenchmark extends StrictLogging {

  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  object Conf extends ZookeeperConf
  Conf.verify()

  val curator: CuratorFramework = CuratorFrameworkFactory.newClient(
    Conf.zkHosts,
    Conf.zooKeeperSessionTimeout().toInt,
    Conf.zooKeeperConnectionTimeout().toInt,
    new BoundedExponentialBackoffRetry(Conf.zooKeeperOperationBaseRetrySleepMs(), Conf.zooKeeperTimeout().toInt, Conf.zooKeeperOperationMaxRetries())
  )
  curator.start()

  lazy val settings: AsyncCuratorBuilderSettings = new AsyncCuratorBuilderSettings(createOptions = Set(CreateOption.createParentsIfNeeded), compressedData = false)
  lazy val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(curator, settings)
  lazy val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(factory, parallelism = 16)

  lazy val repository: TemplateRepository = new TemplateRepository(store)

  val random = new Random()

  def randomPathId = PathId(s"/sleep-${random.alphanumeric.take(8).mkString}")
  def randomPathId(prefix: String) = PathId(s"/$prefix/sleep-${random.alphanumeric.take(8).mkString}")
  def randomRangedPathId(prefix: String, maxRange: Int) = PathId(s"/$prefix/sleep-${random.nextInt(maxRange) + 1}")
  /**
    * Return an [[AppDefinition]] with give pathId. We simulate big app definitions by creating a label with a given value length.
    * The total size of the app definition will be approx. label size + 159 bytes where 159bytes is the size of the serialized
    * app definition alone.
    * Note: the resulting ZK node size is ~25% smaller then calculated since protubuf is doing a decent job compressing the label value.
    *
    * @param pathId
    * @param labelSize
    * @return
    */
  def app(pathId: PathId, labelSize: Int = 1): AppDefinition = AppDefinition(id = pathId, labels = Map("a" -> random.alphanumeric.take(labelSize).mkString))

  def template(pathId: PathId, version: Int, labelSize: Int = 1): Versioned = Versioned(app(pathId, labelSize), version)
  def randomTemplate: Versioned = template(randomPathId, 1)

  // An map of node size to number of nodes of that size. Used for read, update and delete benchmarks. Note that
  // different number of nodes is used depending on the node data size e.g. creating 10K nodes with 1Mb data is not
  // practical since it will result in 10Gb of data.
  type NodeSize = Int
  type NodeNumber = Int
  val params = Map[NodeSize, NodeNumber]((100, 10000), (1024, 10000), (10240, 10000), (102400, 10000))

  /**
    * Helper method to pre-populate Zookeeper with data. By default nodes are created with the name:
    * /$size_sleep-[1-10000] e.g. "/templates/01/1024_sleep-163" where every node is created in a bucket.
    *
    * @param args
    */
  def main(args: Array[String]): Unit = {
    def populate(size: Int, num: Int) = {
      Source(1 to num)
        .map(i => PathId(s"/$size/sleep-$i"))
        .map(pathId => template(pathId, 1, labelSize = size))
        .via(repository.createFlow)
        .runWith(Sink.ignore)
    }

    Await.result(
      Source.fromIterator(() => params.iterator)
        .map{ p => logger.info(s"Storing ${p._2} app definitions of ~${p._1}b size"); p }
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
@Measurement(iterations = 15, time = 1, timeUnit = TimeUnit.SECONDS)
class TemplateRepositoryBenchmark extends StrictLogging {

  import TemplateRepositoryBenchmark._

  /** Node data size */
  @Param(value = Array("100", "1024", "10240", "102400"))
  var size: Int = _

  @Benchmark
  def create(hole: Blackhole) = {
    val res = Await.result(
      repository.create(template(randomPathId(size.toString), version = 1, labelSize = size)),
      Duration.Inf)
    hole.consume(res)
  }

  @Benchmark
  def read(hole: Blackhole) = {
    val res = Await.result(repository.read(randomRangedPathId(size.toString, params(size)), 1), Duration.Inf)
    hole.consume(res)
  }

  @Benchmark
  def delete(hole: Blackhole) = {
    val res = Await.result(repository.delete(randomRangedPathId(size.toString, params(size))), Duration.Inf)
    hole.consume(res)
  }

  @TearDown(Level.Trial)
  def close(): Unit = {
    curator.close()
    Await.result(system.terminate(), Duration.Inf)
  }
}