package mesosphere.marathon.benchmarks.storage.zk

import java.nio.file.Files

import mesosphere.marathon.benchmarks.Benchmark
import mesosphere.marathon.integration.setup.ProcessKeeper
import mesosphere.marathon.test.zk.NoRetryPolicy
import mesosphere.util.PortAllocator
import mesosphere.util.state.zk.RichCuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.zookeeper.KeeperException.{ NoNodeException, NodeExistsException }
import org.scalameter.api._

import scala.collection.immutable.IndexedSeq
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.async.Async.{ async, await }

class ZkStorageBenchmark extends Benchmark {
  // scalastyle:off magic.number
  val sizes = Gen.exponential("nodes")(256, 262144, 2)
  // scalastyle:on
  val zkClient = {
    val port = PortAllocator.ephemeralPort()
    val dir = Files.createTempDirectory("zk-benchmark").toFile
    dir.deleteOnExit()
    ProcessKeeper.startZooKeeper(port, dir.getAbsolutePath)
    val c = CuratorFrameworkFactory.newClient(s"127.0.0.1:$port", NoRetryPolicy)
    c.start()
    c
  }
  val rootClient = new RichCuratorFramework(zkClient)
  val flatClient = new RichCuratorFramework(zkClient.usingNamespace("flat"))
  val nestedClient = new RichCuratorFramework(zkClient.usingNamespace("nested"))
  var ranSetup: Boolean = false
  var ranTeardown: Boolean = false

  override def warmer: Warmer = Warmer.Zero

  def path(num: Int): String = s"/$num"

  def nestedPath(path: Int): String = {
    val folder = path % 16
    s"/$folder/$path"
  }

  def createRoots(): Unit = {
    val roots = rootClient.create("/flat") +: 0.until(16).map { i =>
      rootClient.create(s"/nested/$i", creatingParentsIfNeeded = true)
    }
    val futures = roots.map(_.recover {
      case _: NodeExistsException => ""
    })
    Await.result(Future.sequence(futures), Duration.Inf)
  }

  def createFlat(size: Int): Future[IndexedSeq[String]] = {
    val futures = 0.until(size).map { entry =>
      flatClient.create(path(entry))
    }
    Future.sequence(futures)
  }

  def createNested(size: Int): Future[IndexedSeq[String]] = {
    val futures = 0.until(size).map { entry =>
      nestedClient.create(nestedPath(entry))
    }
    Future.sequence(futures)
  }

  def deleteChildren(): Unit = {
    val flat = async {
      val children = await(rootClient.children("/flat")).children
      val deleteAll = children.map(c => flatClient.delete(s"/$c").recover {
        case _: NoNodeException => ""
      })
      await(Future.sequence(deleteAll))
    }
    val nested = async {
      val allChildrenFutures = 0.until(16).map { i =>
        nestedClient.children(s"/$i").map(_.children.map(c => s"/$i/$c"))
      }
      val children = await(Future.sequence(allChildrenFutures)).flatten
      val deleteAll = children.map(nestedClient.delete(_).recover {
        case _: NoNodeException => ""
      })
      await(Future.sequence(deleteAll))
    }
    Await.result(flat, Duration.Inf)
    Await.result(nested, Duration.Inf)
  }

  override def beforeAll(): Unit = {
    createRoots()
  }

  override def afterAll(): Unit = {
    zkClient.close()
    ProcessKeeper.stopAllProcesses()
  }

  override def defaultConfig: Context = Context(exec.benchRuns -> 2)

  performance of ("Zookeeper Storage") in {
    measure method ("flat") in {
      measure method ("create") in {
        using(sizes) tearDown (_ => deleteChildren) in { size =>
          Await.result(createFlat(size), Duration.Inf)
        }
      }
      measure method ("all children") in {
        using(sizes) setUp (createFlat) tearDown (_ => deleteChildren()) in { _ =>
          Await.result(flatClient.children("/"), Duration.Inf)
        }
      }
      measure method ("subset of children") in {
        using(sizes) setUp (createFlat) tearDown (_ => deleteChildren()) in { _ =>
          // can't do that...
          Await.result(flatClient.children("/"), Duration.Inf)
        }
      }
      measure method ("add a new child") in {
        using(sizes) setUp (createFlat) tearDown (_ => deleteChildren()) in { _ =>
          Await.result(flatClient.create("/new-child"), Duration.Inf)
        }
      }
      measure method ("delete child") in {
        using(sizes) setUp { size =>
          createFlat(size)
          Await.result(flatClient.create("/delete-me"), Duration.Inf)
        } tearDown (_ => deleteChildren()) in { _ =>
          Await.result(flatClient.delete("/delete-me"), Duration.Inf)
        }
      }
    }

    measure method ("nested") in {
      measure method ("create") in {
        using(sizes) tearDown (_ => deleteChildren) in { size =>
          Await.result(createNested(size), Duration.Inf)
        }
      }

      measure method ("children") in {
        using(sizes) setUp (createNested) tearDown (_ => deleteChildren()) in { _ =>
          val allChildren = 0.until(16).map { i =>
            nestedClient.children(s"/$i")
          }
          Await.result(Future.sequence(allChildren), Duration.Inf)
        }
      }
      measure method ("subset of children") in {
        using(sizes) setUp (createNested) tearDown (_ => deleteChildren()) in { _ =>
          Await.result(nestedClient.children("/1"), Duration.Inf)
        }
      }
      measure method ("add new child") in {
        using(sizes) setUp (createNested) tearDown (_ => deleteChildren()) in { _ =>
          Await.result(nestedClient.create("/1/new-child"), Duration.Inf)
        }
      }
      measure method ("delete child") in {
        using(sizes) setUp { size =>
          createNested(size)
          Await.result(nestedClient.create("/1/delete-me"), Duration.Inf)
        } tearDown (_ => deleteChildren()) in { _ =>
          Await.result(nestedClient.delete("/1/delete-me"), Duration.Inf)
        }
      }
    }
  }
}

