package mesosphere.marathon.integration.setup

import java.lang.Thread.UncaughtExceptionHandler
import java.nio.file.{ Files, Path }
import java.util.concurrent.Semaphore

import com.twitter.zk.ZkClient
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.impl.zk.{ NoRetryPolicy, RichCuratorFramework }
import mesosphere.marathon.stream._
import mesosphere.marathon.util.Lock
import mesosphere.util.PortAllocator
import org.apache.commons.io.FileUtils
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.{ CuratorFramework, CuratorFrameworkFactory }
import org.apache.zookeeper.ZooDefs.Ids
import org.apache.zookeeper.server.{ ServerConfig, ZooKeeperServerMain }
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.Try

/**
  * Runs ZooKeeper in memory at the given port.
  * The server can be started and stopped at will.
  *
  * close() should be called when the server is no longer necessary (e.g. try-with-resources)
  *
  * @param autoStart Start zookeeper in the background
  * @param port The port to run ZK on
  */
class ZookeeperServer(
    autoStart: Boolean = true,
    val port: Int = PortAllocator.ephemeralPort()) extends AutoCloseable with StrictLogging {
  private var closing = false
  private val workDir: Path = Files.createTempDirectory("zk")
  private val semaphore = new Semaphore(0)
  private val config = {
    val config = new ServerConfig
    config.parse(Array(port.toString, workDir.toFile.getAbsolutePath))
    config
  }
  private val zk = new ZooKeeperServerMain with AutoCloseable {
    def close(): Unit = super.shutdown()
  }
  private val thread = new Thread(new Runnable {
    override def run(): Unit = {
      while (!closing) {
        zk.runFromConfig(config)
        try {
          semaphore.acquire()
        } catch {
          case _: InterruptedException =>
            closing = true
        }
      }
    }
  }, s"Zookeeper-$port")
  thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
    override def uncaughtException(thread: Thread, throwable: Throwable): Unit = {
      logger.error(s"Error in Zookeeper $port", throwable)
    }
  })
  private var started = false
  if (autoStart) {
    start()
  }

  val connectUri = s"127.0.0.1:$port"

  def start(): Unit = if (!started) {
    if (thread.getState == Thread.State.NEW) {
      thread.start()
    }
    started = true
    semaphore.release()
  }

  def stop(): Unit = if (started) {
    zk.close()
    started = false
  }

  override def close(): Unit = {
    closing = true
    Try(stop())
    Try(FileUtils.deleteDirectory(workDir.toFile))
    thread.interrupt()
    thread.join()
  }
}

object ZookeeperServer {
  def apply(
    autoStart: Boolean = true,
    port: Int = PortAllocator.ephemeralPort()): ZookeeperServer =
    new ZookeeperServer(autoStart, port)
}

trait ZookeeperServerTest extends BeforeAndAfterAll { this: Suite with ScalaFutures =>
  val zkServer = ZookeeperServer(autoStart = false)
  private val clients = Lock(ListBuffer.empty[CuratorFramework])
  private val twitterClients = Lock(ListBuffer.empty[ZkClient])

  def zkClient(retryPolicy: RetryPolicy = NoRetryPolicy, namespace: Option[String] = None): RichCuratorFramework = {
    zkServer.start()
    val client = CuratorFrameworkFactory.newClient(zkServer.connectUri, retryPolicy)
    client.start()
    val actualClient = namespace.fold(client) { ns =>
      RichCuratorFramework(client).create(s"/$namespace").futureValue(Timeout(10.seconds))
      client.usingNamespace(ns)
    }
    // don't need to add the actualClient (namespaced clients don't need to be closed)
    clients(_ += client)
    actualClient
  }

  def twitterZkClient(): ZkClient = {
    zkServer.start()
    val timeout = com.twitter.util.TimeConversions.intToTimeableNumber(10).minutes
    implicit val timer = com.twitter.util.Timer.Nil

    val client = ZkClient(zkServer.connectUri, timeout).withAcl(Ids.OPEN_ACL_UNSAFE.toSeq)
    twitterClients(_ += client)
    client
  }

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    zkServer.start()
  }

  abstract override def afterAll(): Unit = {
    clients { c =>
      c.foreach(_.close())
      c.clear()
    }
    twitterClients { c =>
      c.foreach(_.release())
      c.clear()
    }
    zkServer.close()
    super.afterAll()
  }
}
