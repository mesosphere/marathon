package mesosphere.marathon
package integration.setup

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.LifecycleState
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
  * We wrap ZookeeperServer to give it an interface more consistent with how we start mesos and forked marathon
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
  private var running = autoStart
  private val zkServer = new TestingServer(config, autoStart)

  def connectUri = zkServer.getConnectString
  /**
    * Starts or restarts the server. If the server is currently running it will be stopped
    * and restarted. If it's not currently running then it will be started. If
    * it has been closed (had close() called on it) then an exception will be
    * thrown.
    */
  def start(): Unit = synchronized {
    /* With Curator's TestingServer, if you call start() after stop() was called, then, sadly, nothing is done.
     * However, restart works for both the first start and second start.
     *
     * We make the start method idempotent by only calling restart if the process isn't already running, matching the
     * start/stop behavior of LocalMarathon.
     */
    if (!running) {
      zkServer.restart()
      running = true
    }
  }

  /**
    * Stop the server without deleting the temp directory
    */
  def stop(): Unit = synchronized {
    if (running) {
      zkServer.stop()
      running = false
    }
  }

  /**
    * Close the server and any open clients and delete the temp directory
    */
  def close(): Unit =
    zkServer.close()
}

trait ZookeeperServerTest extends BeforeAndAfterAll { this: Suite with ScalaFutures =>
  val zkServer = ZookeeperServer(autoStart = false)
  private val clients = Lock(mutable.Buffer.empty[CuratorFramework])

  def zkClient(retryPolicy: RetryPolicy = NoRetryPolicy, namespace: Option[String] = None): RichCuratorFramework = {
    zkServer.start()
    val client = CuratorFrameworkFactory.newClient(zkServer.connectUri, retryPolicy)
    client.start()
    val richClient = RichCuratorFramework(client)
    richClient.blockUntilConnected(LifecycleState.WatchingJVM)
    val actualClient = namespace.fold(client) { ns =>
      richClient.create(s"/$namespace").futureValue(Timeout(10.seconds))
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
