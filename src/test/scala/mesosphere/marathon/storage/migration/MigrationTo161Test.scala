package mesosphere.marathon
package storage.migration

import java.time.{OffsetDateTime, ZoneOffset}

import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.store.impl.zk._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.store.ZkStoreSerialization
import mesosphere.marathon.test.GroupCreation
import MigrationTo161.oldZkIdPath
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.metrics.dummy.DummyMetrics

import scala.concurrent.{ExecutionContextExecutor, Future}

class MigrationTo161Test extends AkkaUnitTest with GroupCreation with StrictLogging {

  "Migration to 1.6.1" should {
    "do migration for stored versions of apps" in new Fixture {
      initMocks()
      persistenceStore.ids[PathId, AppDefinition]()(any) returns Source(List(appDefinition.id))

      MigrationTo161.migrateApps(persistenceStore, client).futureValue

      verify(persistenceStore, once).ids()(any)
      verify(client, once).children(equalTo(appZkId.path))
      verify(client, once).data(equalTo(oldZkIdPath(versionedAppZkId)), any)
      verify(client, once).setData(equalTo(versionedAppZkId.path), equalTo(ByteString("app")), any, any)
      verify(client, once).delete(equalTo(oldZkIdPath(versionedAppZkId)), any, any, any)
    }

    "do migration for stored versions of pods" in new Fixture {
      initMocks()
      persistenceStore.ids[PathId, PodDefinition]()(any) returns Source(List(podDefinition.id))

      MigrationTo161.migratePods(persistenceStore, client).futureValue

      verify(persistenceStore, once).ids()(any)
      verify(client, once).children(equalTo(podZkId.path))
      verify(client, once).data(equalTo(oldZkIdPath(versionedPodZkId)), any)
      verify(client, once).setData(equalTo(versionedPodZkId.path), equalTo(ByteString("pod")), any, any)
      verify(client, once).delete(equalTo(oldZkIdPath(versionedPodZkId)), any, any, any)
    }

    "do not migrate if the stored version is already correct" in new Fixture {
      initMocks()
      persistenceStore.ids[PathId, AppDefinition]()(any) returns Source(List(appDefinition.id))
      client.children(equalTo(appZkId.path)) returns (Future.successful(Children(appZkId.path, null, Seq(targetVersionString))))

      MigrationTo161.migrateApps(persistenceStore, client).futureValue

      verify(persistenceStore, once).ids()(any)
      verify(client, once).children(equalTo(appZkId.path))
      verify(client, never).data(any, any)
      verify(client, never).setData(any, any, any, any)
      verify(client, never).delete(any, any, any, any)
    }

  }

  private class Fixture {

    val now = Timestamp(OffsetDateTime.of(2015, 2, 3, 12, 30, 0, 0, ZoneOffset.UTC))

    class ZkStoreWithMockedMetrics(client: RichCuratorFramework) extends ZkPersistenceStore(DummyMetrics, client)
    val persistenceStore: PersistenceStore[ZkId, String, ZkSerialized] = mock[PersistenceStore[ZkId, String, ZkSerialized]]

    val client = mock[RichCuratorFramework]
    implicit lazy val mat: Materializer = ActorMaterializer()
    implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher

    def appDefinition = AppDefinition(
      id = PathId("/app")
    )

    val appZkId = ZkStoreSerialization.appDefResolver.toStorageId(appDefinition.id, None)

    def podDefinition = PodDefinition(
      id = PathId("/pod")
    )

    val podZkId = ZkStoreSerialization.podDefResolver.toStorageId(podDefinition.id, None)

    val version = OffsetDateTime.of(2015, 2, 3, 12, 30, 13, 123456789, ZoneOffset.UTC)

    val versionedAppZkId = appZkId.copy(version = Some(version))

    val versionedPodZkId = podZkId.copy(version = Some(version))

    val versionString = version.toString

    val targetVersionString = version.format(Timestamp.formatter)

    def initMocks() = {

      client.children(equalTo(appZkId.path)) returns (Future.successful(Children(appZkId.path, null, Seq(versionString))))
      client.children(equalTo(podZkId.path)) returns (Future.successful(Children(podZkId.path, null, Seq(versionString))))

      client.data(equalTo(MigrationTo161.oldZkIdPath(versionedAppZkId)), any) returns Future.successful(GetData(MigrationTo161.oldZkIdPath(appZkId), null, ByteString("app")))
      client.data(equalTo(MigrationTo161.oldZkIdPath(versionedPodZkId)), any) returns Future.successful(GetData(MigrationTo161.oldZkIdPath(podZkId), null, ByteString("pod")))

      client.setData(equalTo(versionedAppZkId.path), equalTo(ByteString("app")), any, any) returns Future.successful(SetData("foo", null))
      client.setData(equalTo(versionedPodZkId.path), equalTo(ByteString("pod")), any, any) returns Future.successful(SetData("foo", null))

      client.delete(equalTo(MigrationTo161.oldZkIdPath(versionedAppZkId)), any, any, any) returns Future.successful("foo")
      client.delete(equalTo(MigrationTo161.oldZkIdPath(versionedPodZkId)), any, any, any) returns Future.successful("foo")

    }
  }

}
