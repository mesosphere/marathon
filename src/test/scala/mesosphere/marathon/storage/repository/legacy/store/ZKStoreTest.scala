package mesosphere.marathon
package storage.repository.legacy.store

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.codahale.metrics.MetricRegistry
import com.twitter.util.Await
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.stream._
import mesosphere.util.state.PersistentStoreTest
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.ZooDefs.Ids
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.duration._

@IntegrationTest
class ZKStoreTest extends PersistentStoreTest with ZookeeperServerTest {
  import ZKStore._

  implicit val metrics = new Metrics(new MetricRegistry)
  implicit var system: ActorSystem = ActorSystem()

  //
  // See PersistentStoreTests for general store tests
  //

  test("Compatibility to mesos state storage. Write zk read mesos.") {
    val created = persistentStore.create("foo", "Hello".getBytes.toIndexedSeq).futureValue
    val mesosLoaded = mesosStore.load("foo").futureValue
    mesosLoaded should be('defined)
    mesosLoaded.get.bytes should be(created.bytes)

    persistentStore.update(created.withNewContent("Hello again".getBytes.toIndexedSeq)).futureValue
    val mesosLoadUpdated = mesosStore.load("foo").futureValue
    mesosLoadUpdated should be('defined)
    mesosLoadUpdated.get.bytes should be("Hello again".getBytes)
  }

  test("Compatibility to mesos state storage. Write mesos read zk.") {
    val created = mesosStore.create("foo", "Hello".getBytes.toIndexedSeq).futureValue
    val zkLoaded = persistentStore.load("foo").futureValue
    zkLoaded should be('defined)
    zkLoaded.get.bytes should be(created.bytes)

    mesosStore.update(created.withNewContent("Hello again".getBytes.toIndexedSeq)).futureValue
    val zkLoadUpdated = persistentStore.load("foo").futureValue
    zkLoadUpdated should be('defined)
    zkLoadUpdated.get.bytes should be("Hello again".getBytes)
  }

  test("Deeply nested paths are created") {
    val client = persistentStore.client
    val path = client("/s/o/m/e/d/e/e/p/ly/n/e/s/t/e/d/p/a/t/h")
    val store = new ZKStore(client, path, conf, 8, 1024)
    store.markOpen()

    path.exists().asScala.failed.futureValue shouldBe a[NoNodeException]
    store.initialize().futureValue(Timeout(5.seconds))
    path.exists().asScala.futureValue.stat.getVersion should be(0)
  }

  test("Already existing paths are not created") {
    //this parameter is used for futureValue timeouts
    implicit val patienceConfig = PatienceConfig(Span(10, Seconds))

    val client = persistentStore.client
    val path = client("/some/deeply/nested/path")
    path.exists().asScala.failed.futureValue shouldBe a[NoNodeException]

    val store1 = new ZKStore(client, path, conf, 8, 1024)
    store1.markOpen()
    store1.initialize().futureValue
    path.exists().asScala.futureValue.stat.getVersion should be(0)

    val store2 = new ZKStore(client, path, conf, 8, 1024)
    store2.markOpen()
    store2.initialize().futureValue
    path.exists().asScala.futureValue.stat.getVersion should be(0)
  }

  test("Entity nodes greater than the compression limit get compressed") {
    import ZKStore._

    val compress = CompressionConf(true, 0)
    val store = new ZKStore(persistentStore.client, persistentStore.client("/compressed"), compress, 8, 1024)
    store.markOpen()
    store.initialize().futureValue(Timeout(5.seconds))
    val content = 1.to(100).map(num => s"Hello number $num!").mkString(", ").getBytes("UTF-8")

    //entity content is not changed , regardless of comression
    val entity = store.create("big", content.toIndexedSeq).futureValue
    entity.bytes should be(content)

    //the proto that is created is compressed
    val proto = entity.data.toProto(compress)
    proto.getCompressed should be (true)
    proto.getValue.size() should be < content.length

    //the node that is stored is compressed
    val data = entity.node.getData().asScala.futureValue
    data.stat.getDataLength should be < content.length

    //the node that is loaded is uncompressed
    val loaded = store.load("big").futureValue
    loaded should be('defined)
    loaded.get.bytes should be(content)
  }

  lazy val persistentStore: ZKStore = {
    implicit val timer = com.twitter.util.Timer.Nil
    val timeout = com.twitter.util.TimeConversions.intToTimeableNumber(10).minutes
    val client = twitterZkClient().withAcl(Ids.OPEN_ACL_UNSAFE.toSeq)
    val store = new ZKStore(client, client(root), conf, 8, 1024)
    store.markOpen()
    store
  }

  lazy val mesosStore: MesosStateStore = {
    val duration = 30.seconds
    val state = new ZooKeeperState(
      zkServer.connectUri,
      duration.toMillis,
      TimeUnit.MILLISECONDS,
      root
    )
    val store = new MesosStateStore(state, duration)
    store.markOpen()
    store
  }

  val root = s"/${UUID.randomUUID}"
  val conf = CompressionConf(false, 0)

  override def afterAll(): Unit = {
    Await.ready(persistentStore.client.release())
    system.terminate().futureValue
  }
}
