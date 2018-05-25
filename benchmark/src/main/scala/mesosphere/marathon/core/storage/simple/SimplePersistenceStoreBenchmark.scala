package mesosphere.marathon
package core.storage.simple

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import mesosphere.marathon.core.storage.simple.PersistenceStore.Node
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.BoundedExponentialBackoffRetry
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.Random

@State(Scope.Benchmark)
object SimplePersistenceStoreBenchmark {

  implicit lazy val system: ActorSystem = ActorSystem()
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()
  implicit lazy val ec: ExecutionContext = system.dispatcher

  object Conf extends ZookeeperConf
  Conf.verify()

  val curator: CuratorFramework = CuratorFrameworkFactory.newClient(
    Conf.zkHosts,
    Conf.zooKeeperSessionTimeout().toInt,
    Conf.zooKeeperConnectionTimeout().toInt,
    new BoundedExponentialBackoffRetry(Conf.zooKeeperOperationBaseRetrySleepMs(), Conf.zooKeeperTimeout().toInt, Conf.zooKeeperOperationMaxRetries())
  )
  curator.start()

  lazy val store: SimplePersistenceStore = new SimplePersistenceStore(curator)
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
class SimplePersistenceStoreBenchmark {
  import SimplePersistenceStoreBenchmark._

  /** Node data size */
  @Param(value = Array("50", "100", "500", "1024"))
  var size: Int = _

  /** Number of nodes to insert per batch */
  @Param(value = Array("1", "10", "100", "1000"))
  var num: Int = _

  def randomPath(prefix: String = "", size: Int = 10): String =
    s"$prefix/${Random.alphanumeric.take(size).mkString}"

  def nodes = Source(1 to num)
    .map(_ => Node(randomPath("/tests"), ByteString(Random.alphanumeric.take(size).mkString)))

  @Benchmark
  @Fork(1)
  def run(hole: Blackhole) = {
    val res = Await.result(
      nodes
        .via(store.create)
        .runWith(Sink.ignore), Duration.Inf)
    hole.consume(res)
  }

  @TearDown(Level.Trial)
  def close(): Unit = {
    curator.close()
    Await.result(system.terminate(), Duration.Inf)
  }
}