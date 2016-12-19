package mesosphere.marathon.integration.setup

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.impl.zk.{ NoRetryPolicy, RichCuratorFramework }
import mesosphere.marathon.util.Lock
import mesosphere.util.PortAllocator
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.{ CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.test.InstanceSpec
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.collection.mutable
import scala.concurrent.duration._

import org.apache.curator.test.TestingServer

/**
  * Runs ZooKeeper in memory at the given port.
  * The server can be started and stopped at will.
  *
  * close() should be called when the server is no longer necessary (e.g. try-with-resources)
  *
  * @param autoStart Start zookeeper in the background
  * @param port The port to run ZK on
  */
case class ZookeeperServer(
    autoStart: Boolean = true,
    val port: Int = PortAllocator.ephemeralPort()) extends AutoCloseable with StrictLogging {

  private val maxClientConnections = 20
  private val config = {
    new InstanceSpec(
      null, // auto-create workdir
      port,
      -1, // random electionPort
      -1, // random quorumPort
      true, // deleteDataDirectoryOnClose = true
      -1, // default serverId
      -1, // default tickTime
      maxClientConnections
    )
  }
  private val zkServer = new TestingServer(config, autoStart)

  val connectUri = s"127.0.0.1:$port"

  def start(): Unit = zkServer.start()
  def stop(): Unit = zkServer.stop()
  def close(): Unit = zkServer.close()
}

trait ZookeeperServerTest extends BeforeAndAfterAll { this: Suite with ScalaFutures =>
  val zkServer = ZookeeperServer(autoStart = false)
  private val clients = Lock(mutable.Buffer.empty[CuratorFramework])

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

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    zkServer.start()
  }

  abstract override def afterAll(): Unit = {
    clients { c =>
      c.foreach(_.close())
      c.clear()
    }

    zkServer.close()
    super.afterAll()
  }
}
