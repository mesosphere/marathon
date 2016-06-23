package mesosphere.marathon.benchmarks.storage.zk

import java.nio.file.Files

import mesosphere.marathon.benchmarks.Benchmark
import mesosphere.marathon.integration.setup.ProcessKeeper
import mesosphere.marathon.test.zk.NoRetryPolicy
import mesosphere.util.PortAllocator
import mesosphere.util.state.zk.RichCuratorFramework
import org.apache.curator.framework.{ CuratorFramework, CuratorFrameworkFactory }
import org.scalameter.api._

import scala.collection.immutable.IndexedSeq
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.Try
import scala.async.Async.{ async, await }

class ZkStorageBenchmark extends Benchmark {
  // scalastyle:off magic.number
  val sizes = Gen.range("Number of Nodes")(1000, 100000, 10000)
  // scalastyle:on
  var port = Option.empty[Int]
  var client = Option.empty[CuratorFramework]

  def path(num: Int): String = s"/$num"

  def nestedPath(path: Int): String = {
    val folder = path % 16
    s"/$folder/$path"
  }

  def beforeAll(): Unit = {
    port = Some(PortAllocator.ephemeralPort())
    val dir = Files.createTempDirectory("zk").toFile
    dir.deleteOnExit()
    ProcessKeeper.startZooKeeper(port.get, dir.getAbsolutePath)
    client = Some(CuratorFrameworkFactory.newClient(s"127.0.0.1:${port.get}", NoRetryPolicy))
    client.foreach(_.start())
  }

  def afterAll(): Unit = {
    client.foreach(_.close())
    port = None
    client = None
    ProcessKeeper.stopAllProcesses()
  }

  def before(size: Int): Unit = {
    client.foreach { c =>
      Try(c.create().creatingParentContainersIfNeeded().forPath("/flat"))
      0.until(16).foreach { i =>
        Try(c.create().creatingParentContainersIfNeeded().forPath(s"/nested/$i"))
      }
    }
  }

  def after(size: Int): Unit = {
    client.foreach { c =>
      val rc = new RichCuratorFramework(c)
      val flatFuture = async {
        val deleteChildren = await(rc.children("/flat")).children.map(child =>
          rc.delete(s"/flat/$child", deletingChildrenIfNeeded = true)
        )
        await(Future.sequence(deleteChildren))
        await(rc.delete("/flat", deletingChildrenIfNeeded = true))
      }
      val nestedFuture = async {
        val allChildren = 0.until(16).map { i =>
          rc.children(s"/nested/$i").map(_.children.map(c => s"/nested/$i/$c"))
        }
        val deleteChildren = await(Future.sequence(allChildren))
          .flatten.map(rc.delete(_, deletingChildrenIfNeeded = true))
        await(Future.sequence(deleteChildren))
        val deleteFolders = 0.until(16).map { i =>
          rc.delete(s"/nested/$i")
        }
        await(Future.sequence(deleteFolders))
        await(rc.delete(s"/nested", deletingChildrenIfNeeded = true))
      }
      Await.result(flatFuture, Duration.Inf)
      Await.result(nestedFuture, Duration.Inf)
    }
  }

  def createFlat(client: CuratorFramework, size: Int): Future[IndexedSeq[String]] = {
    val c = new RichCuratorFramework(client.usingNamespace("flat"))
    val futures = 0.until(size).map { entry =>
      c.create(path(entry), creatingParentContainersIfNeeded = true)
    }
    Future.sequence(futures)
  }

  def createNested(client: CuratorFramework, size: Int): Future[IndexedSeq[String]] = {
    val c = new RichCuratorFramework(client.usingNamespace("nested"))
    val futures = 0.until(size).map { entry =>
      c.create(nestedPath(entry), creatingParentContainersIfNeeded = true)
    }
    Future.sequence(futures)
  }

  performance of ("Zookeeper Storage") in {
    measure method ("creation - flat") in {
      using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp(before).tearDown(after) in { size =>
        Await.result(createFlat(client.get, size), Duration.Inf)
      }
    }
    measure method ("list children - flat") in {
      using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
        before(size)
        createFlat(client.get, size)
      }.tearDown(after) in { size =>
        val rc = new RichCuratorFramework(client.get.usingNamespace("flat"))
        Await.result(rc.children("/"), Duration.Inf)
      }

      measure method ("add child - flat") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
          before(size)
          createFlat(client.get, size)
        }.tearDown(after).in { size =>
          Await.result(new RichCuratorFramework(client.get.usingNamespace("flat")).create("/new-child"), Duration.Inf)
        }
      }

      measure method ("delete child - flat") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
          before(size)
          createFlat(client.get, size)
          Await.result(new RichCuratorFramework(client.get.usingNamespace("flat")).create("/delete-me"), Duration.Inf)
        }.tearDown(after).in { size =>
          Await.result(new RichCuratorFramework(client.get.usingNamespace("flat")).delete("/delete-me"), Duration.Inf)
        }
      }

      measure method ("create - nested") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp(before).tearDown(after) in { size =>
          Await.result(createNested(client.get, size), Duration.Inf)
        }
      }

      measure method ("list all children - nested") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
          before(size)
          createNested(client.get, size)
        }.tearDown(after).in { size =>
          val c = new RichCuratorFramework(client.get.usingNamespace("nested"))
          val futures = 0.until(16).map { i =>
            c.children(s"/$i")
          }
          Await.result(Future.sequence(futures), Duration.Inf)
        }
      }

      measure method ("list a group of children - nested") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
          before(size)
          createNested(client.get, size)
        }.tearDown(after).in { size =>
          val c = new RichCuratorFramework(client.get.usingNamespace("nested"))
          Await.result(c.children(s"/1"), Duration.Inf)
        }
      }

      measure method ("add child - nested") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
          before(size)
          createNested(client.get, size)
        }.tearDown(after).in { size =>
          val c = new RichCuratorFramework(client.get.usingNamespace("nested"))
          Await.result(c.create("/1/new-node"), Duration.Inf)
        }
      }

      measure method ("delete child - nested") in {
        using(sizes).beforeTests(beforeAll()).afterTests(afterAll()).setUp { size =>
          before(size)
          createNested(client.get, size)
          val c = new RichCuratorFramework(client.get.usingNamespace("nested"))
          Await.result(c.create("/1/delete-node"), Duration.Inf)
        }.tearDown(after).in { size =>
          val c = new RichCuratorFramework(client.get.usingNamespace("nested"))
          Await.result(c.delete("/1/delete-node"), Duration.Inf)
        }
      }
    }
  }
}

