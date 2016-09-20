package mesosphere.marathon.storage.repository.legacy.store

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
import org.scalatest._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.duration._

class ZKStoreTest extends PersistentStoreTest with ZookeeperServerTest with Matchers with ScalaFutures {
  import ZKStore._

  implicit val metrics = new Metrics(new MetricRegistry)
  implicit var system: ActorSystem = ActorSystem.apply()

  //
  // See PersistentStoreTests for general store tests
  //

  test("Compatibility to mesos state storage. Write zk read mesos.") {
    val created = persistentStore.create("foo", "Hello".getBytes).futureValue
    val mesosLoaded = mesosStore.load("foo").futureValue
    mesosLoaded.should(be.apply('defined))
    mesosLoaded.get.bytes.should(be.apply(created.bytes))

    persistentStore.update(created.withNewContent("Hello again".getBytes)).futureValue
    val mesosLoadUpdated = mesosStore.load("foo").futureValue
    mesosLoadUpdated.should(be.apply('defined))
    mesosLoadUpdated.get.bytes.should(be.apply("Hello again".getBytes))
  }

  test("Compatibility to mesos state storage. Write mesos read zk.") {
    val created = mesosStore.create("foo", "Hello".getBytes).futureValue
    val zkLoaded = persistentStore.load("foo").futureValue
    zkLoaded.should(be.apply('defined))
    zkLoaded.get.bytes.should(be.apply(created.bytes))

    mesosStore.update(created.withNewContent("Hello again".getBytes)).futureValue
    val zkLoadUpdated = persistentStore.load("foo").futureValue
    zkLoadUpdated.should(be.apply('defined))
    zkLoadUpdated.get.bytes.should(be.apply("Hello again".getBytes))
  }

  test("Deeply nested paths are created") {
    val client = persistentStore.client
    val path = client.apply("/s/o/m/e/d/e/e/p/ly/n/e/s/t/e/d/p/a/t/h")
    val store = new ZKStore(client, path, conf, 8, 1024)
    path.exists.apply().asScala.failed.futureValue.shouldBe(a[NoNodeException])
    store.initialize().futureValue(Timeout.apply(5.seconds))
    path.exists.apply().asScala.futureValue.stat.getVersion.should(be.apply(0))
  }

  test("Already existing paths are not created") {
    //this parameter is used for futureValue timeouts
    implicit val patienceConfig = PatienceConfig.apply(Span.apply(10, Seconds))

    val client = persistentStore.client
    val path = client.apply("/some/deeply/nested/path")
    path.exists.apply().asScala.failed.futureValue.shouldBe(a[NoNodeException])
    new ZKStore(client, path, conf, 8, 1024).initialize().futureValue
    path.exists.apply().asScala.futureValue.stat.getVersion.should(be.apply(0))
    new ZKStore(client, path, conf, 8, 1024).initialize().futureValue
    path.exists.apply().asScala.futureValue.stat.getVersion.should(be.apply(0))
  }

  test("Entity nodes greater than the compression limit get compressed") {
    import ZKStore._

    val compress = CompressionConf.apply(true, 0)
    val store = new ZKStore(persistentStore.client, persistentStore.client.apply("/compressed"), compress, 8, 1024)
    store.initialize().futureValue(Timeout.apply(5.seconds))
    val content = 1.to(100).map(num => StringContext.apply("Hello number ", "!").s(num)).mkString(", ").getBytes("UTF-8")

    //entity content is not changed , regardless of comression
    val entity = store.create("big", content).futureValue
    entity.bytes.should(be.apply(content))

    //the proto that is created is compressed
    val proto = entity.data.toProto(compress)
    proto.getCompressed.should(be.apply (true))
    proto.getValue.size().should(be < content.length)

    //the node that is stored is compressed
    val data = entity.node.getData.apply().asScala.futureValue
    data.stat.getDataLength should be < content.length

    //the node that is loaded is uncompressed
    val loaded = store.load("big").futureValue
    loaded should be.apply('defined)
    loaded.get.bytes should be.apply(content)
  }

  lazy val persistentStore: ZKStore = {
    implicit val timer = com.twitter.util.Timer.Nil
    val timeout = com.twitter.util.TimeConversions.intToTimeableNumber(10).minutes
    val client = twitterZkClient().withAcl(Ids.OPEN_ACL_UNSAFE.toIndexedSeq)
    new ZKStore(client, client.apply(root), conf, 8, 1024)
  }

  lazy val mesosStore: MesosStateStore = {
    val duration = 30.seconds
    val state = new ZooKeeperState(
      zkServer.connectUri,
      duration.toMillis,
      TimeUnit.MILLISECONDS,
      root
    )
    new MesosStateStore(state, duration)
  }

  val root = StringContext.apply("/", "").s(UUID.randomUUID)
  val conf = CompressionConf.apply(false, 0)

  override def afterAll(): Unit = {
    Await.ready(persistentStore.client.release())
    system.terminate().futureValue
  }
}
