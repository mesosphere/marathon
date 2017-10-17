package mesosphere.marathon
package core.storage.store.impl.zk

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.{ ActorMaterializer, Materializer }
import mesosphere.marathon.core.async.ExecutionContexts._
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.storage.{ CuratorZk, StorageConf }
import mesosphere.marathon.storage.repository.StoredGroup
import mesosphere.marathon.storage.store.ZkStoreSerialization
import mesosphere.marathon.upgrade.DependencyGraphBenchmark
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.async.Async._
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._

object ZkPresistenceStoreBenchmark {

  implicit lazy val system: ActorSystem = ActorSystem()
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()

  object Conf extends StorageConf with NetworkConf {
    override def availableFeatures: Set[String] = Set.empty
  }
  Conf.verify()
  val lifecycleState = LifecycleState.WatchingJVM
  val curator = CuratorZk(Conf, lifecycleState)
  val zkStore = curator.leafStore

  val rootGroup = DependencyGraphBenchmark.rootGroup
  val storedGroup = StoredGroup.apply(rootGroup)
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class ZkPresistenceStoreBenchmark {
  import ZkPresistenceStoreBenchmark._
  import ZkStoreSerialization._

  @Benchmark
  def storeAndRemoveGroup(hole: Blackhole): Unit = {
    val done = Promise[Done]
    val pipeline: Future[Done] = async {
      await(zkStore.store(storedGroup.id, storedGroup))
      val delete = Future.sequence(rootGroup.groupsById.keys.map { id => zkStore.deleteAll(id)(appDefResolver) })
      await(delete)
      Done
    }
    done.completeWith(pipeline)

    // Poll until we are done
    while (!done.isCompleted) { Thread.sleep(100) }
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    println("Shutting down...")
    curator.client.close()
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }
}
