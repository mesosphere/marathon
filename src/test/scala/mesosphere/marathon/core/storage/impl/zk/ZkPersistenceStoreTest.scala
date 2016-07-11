package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime
import java.util.UUID

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import com.codahale.metrics.MetricRegistry
import com.google.protobuf.MessageLite
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage._
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics

trait TestClass1Implicits {
  implicit val zkIdResolver = new IdResolver[String, ZkId, TestClass1, ZkSerialized] {
    override def fromStorageId(path: ZkId): String = path.id.replaceAll("_", "/")

    override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId = {
      ZkId(category = "test-class", id.replaceAll("/", "_"), version)
    }

    override def toStorageCategory: ZkId = ZkId("test-class", "", None)

    override val maxVersions: Int = 2
  }

  implicit def zkMarshal[Proto <: MessageLite,
                         T <: MarathonState[Proto]]: Marshaller[MarathonState[Proto], ZkSerialized] =
    Marshaller.opaque { (a: MarathonState[Proto]) =>
      ZkSerialized(ByteString(a.toProto.toByteArray))
    }

  def zkUnmarshaller[Proto <: MessageLite,
                     T <: MarathonState[Proto]](proto: MarathonProto[Proto, T]): Unmarshaller[ZkSerialized, T] =
    Unmarshaller.strict { (a: ZkSerialized) => proto.fromProtoBytes(a.bytes) }

  implicit def zkTc1Unmarshaller: Unmarshaller[ZkSerialized, TestClass1] =
    zkUnmarshaller(new TestClass1.Builder)
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
    implicit val metrics = new Metrics(new MetricRegistry)
    new ZkPersistenceStore(client.usingNamespace(root), 5)
  }

  "ZookeeperPersistenceStore" should {
    behave like singleTypeStore(defaultStore)
  }
}

