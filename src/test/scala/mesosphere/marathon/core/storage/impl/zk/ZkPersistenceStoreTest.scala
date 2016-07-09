/*package mesosphere.marathon.core.storage.impl.zk

import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStoreTest, TestClass1 }
import mesosphere.marathon.integration.setup.ZookeeperServerTest

trait TestClass1Implicits {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  implicit val byteStringMarshaller: Marshaller[TestClass1, ZkSerialized] =
    Marshaller.opaque { (tc: TestClass1) =>
      val bytes = ByteString.newBuilder
      val strBytes = tc.str.getBytes(StandardCharsets.UTF_8)
      bytes.putInt(strBytes.length)
      bytes.putBytes(strBytes)
      bytes.putInt(tc.int)
      ZkSerialized((bytes.result())
    }

  implicit val byteStringUnmarshaller: Unmarshaller[ZkSerialized, TestClass1] =
    Unmarshaller.strict { (zk: ZkSerialized) =>
      val it = zk.bytes.iterator
      val strLen = it.getInt
      val str = new String(it.getBytes(strLen), StandardCharsets.UTF_8)
      TestClass1(str, it.getInt)
    }

  implicit val zkIdResolver = new IdResolver[String, ZkId, TestClass1, ZkSerialized] {
    override def fromStorageId(path: ZkId): String = {
      path.id.replaceFirst("/test-class/", "")
    }

    override def toStorageId(id: String): ZkId = ZkId(if (id.nonEmpty) s"/test-class/$id" else "/test-class")
  }
}

class ZkPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with ZookeeperServerTest with TestClass1Implicits {

  implicit val scheduler = system.scheduler

  val rootId: String = ""
  def createId: String = s"${UUID.randomUUID().toString.replaceAll("-", "_")}"

  lazy val defaultStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = createId
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root))
  }

  "ZookeeperPersistenceStore" should {
    behave like singleTypeStore(defaultStore)
  }
}

*/