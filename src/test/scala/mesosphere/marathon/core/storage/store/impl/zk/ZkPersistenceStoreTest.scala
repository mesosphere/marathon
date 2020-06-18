package mesosphere.marathon
package core.storage.store.impl.zk

import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.mesosphere.utils.zookeeper.ZookeeperServerTest
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.JvmExitsCrashStrategy
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStoreTest, TestClass1}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.Timestamp

trait ZkTestClass1Serialization {
  implicit object ZkTestClass1Resolver extends IdResolver[String, TestClass1, String, ZkId] {
    override def fromStorageId(path: ZkId): String = path.id.replaceAll("_", "/")
    override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId = {
      ZkId(category = "test-class", id.replaceAll("/", "_"), version)
    }
    override val category: String = "test-class"
    override val hasVersions = true
    override def version(tc: TestClass1): OffsetDateTime = tc.version
  }

  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  implicit val tc1ZkMarshal: Marshaller[TestClass1, ZkSerialized] =
    Marshaller.opaque { (a: TestClass1) =>
      val builder = ByteString.newBuilder
      val id = a.str.getBytes(StandardCharsets.UTF_8)
      builder.putInt(id.length)
      builder.putBytes(id)
      builder.putInt(a.int)
      builder.putLong(a.version.toInstant.toEpochMilli)
      builder.putInt(a.version.getOffset.getTotalSeconds)
      ZkSerialized(builder.result())
    }

  implicit val tc1ZkUnmarshal: Unmarshaller[ZkSerialized, TestClass1] =
    Unmarshaller.strict { (a: ZkSerialized) =>
      val it = a.bytes.iterator
      val len = it.getInt
      val str = it.getBytes(len)
      val int = it.getInt
      val time = it.getLong
      val offset = it.getInt
      val version = OffsetDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.ofTotalSeconds(offset))
      TestClass1(new String(str, StandardCharsets.UTF_8), int, version)
    }
}

class ZkPersistenceStoreTest extends AkkaUnitTest with PersistenceStoreTest with ZookeeperServerTest with ZkTestClass1Serialization {

  lazy val rootClient = zkClient()

  private val metrics = DummyMetrics

  def defaultStore: ZkPersistenceStore = {
    val root = UUID.randomUUID().toString
    val client = RichCuratorFramework(zkClient(namespace = Some(root)), JvmExitsCrashStrategy)
    val store = new ZkPersistenceStore(metrics, client)
    store.markOpen()
    store
  }

  behave like basicPersistenceStore("ZookeeperPersistenceStore", defaultStore)
  behave like backupRestoreStore("ZookeeperPersistenceStore", defaultStore)

  "ZkId should trim anything but millis after serialization" in {
    val dateTime = OffsetDateTime.of(2015, 5, 14, 15, 43, 21, 0, ZoneOffset.UTC)
    val withNanos = dateTime.withNano(123456789)
    val zkId = ZkId("cat", "path", Some(withNanos))
    val zkIdVersion = zkId.path.reverse.takeWhile(_ != '/').reverse
    zkIdVersion shouldEqual "2015-05-14T15:43:21.123Z"
    ZkId("cat", "path", Some(OffsetDateTime.parse(zkIdVersion))).path shouldEqual zkId.path
  }

  def trimmingTest(offsetDateTime: OffsetDateTime): Unit = {
    val store = defaultStore

    val offsetDateTimeOnlyMillisStr = offsetDateTime.format(Timestamp.formatter)
    val offsetDateTimeOnlyMillis = OffsetDateTime.parse(offsetDateTimeOnlyMillisStr)
    val tc = TestClass1("abc", 1, offsetDateTime)
    store.store("test", tc).futureValue shouldEqual Done
    store.versions("test").runWith(Sink.seq).futureValue shouldEqual Seq(offsetDateTimeOnlyMillis)
  }
  "handle nanoseconds when providing versions" in {
    val offsetDateTime = OffsetDateTime.of(2015, 2, 3, 12, 30, 15, 123456789, ZoneOffset.UTC)
    trimmingTest(offsetDateTime)
  }
  "handle milliseconds when providing versions" in {
    val offsetDateTime = OffsetDateTime.of(2015, 2, 3, 12, 30, 15, 123000000, ZoneOffset.UTC)
    trimmingTest(offsetDateTime)
  }
}
