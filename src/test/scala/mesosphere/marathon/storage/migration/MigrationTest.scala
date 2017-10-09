package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.scaladsl.Source
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.migration.StorageVersions._
import mesosphere.marathon.storage.repository.legacy.store.{ InMemoryEntity, PersistentEntity, PersistentStore, PersistentStoreManagement }
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.test.Mockito
import org.scalatest.GivenWhenThen

import scala.concurrent.Future

class MigrationTest extends AkkaUnitTest with Mockito with GivenWhenThen {
  implicit private def metrics = new Metrics(new MetricRegistry)

  // scalastyle:off
  private[this] def migration(
    legacyConfig: Option[LegacyStorageConfig] = None,
    persistenceStore: Option[PersistenceStore[_, _, _]] = None,
    appRepository: AppRepository = mock[AppRepository],
    podRepository: PodRepository = mock[PodRepository],
    groupRepository: GroupRepository = mock[GroupRepository],
    deploymentRepository: DeploymentRepository = mock[DeploymentRepository],
    taskRepository: TaskRepository = mock[TaskRepository],
    instanceRepository: InstanceRepository = mock[InstanceRepository],
    taskFailureRepository: TaskFailureRepository = mock[TaskFailureRepository],
    frameworkIdRepository: FrameworkIdRepository = mock[FrameworkIdRepository],
    eventSubscribersRepository: EventSubscribersRepository = mock[EventSubscribersRepository]): Migration = {
    groupRepository.invalidateGroupCache() returns Future.successful(Done)
    new Migration(Set.empty, legacyConfig, persistenceStore, appRepository, podRepository, groupRepository, deploymentRepository,
      taskRepository, instanceRepository, taskFailureRepository, frameworkIdRepository, eventSubscribersRepository)
  }
  // scalastyle:on

  val currentVersion: StorageVersion = if (StorageVersions.current < StorageVersions(1, 3, 0)) {
    StorageVersions(1, 3, 0)
  } else {
    StorageVersions.current
  }

  def mockPersistenceStore(): PersistenceStore[_, _, _] = {
    val mockedStore = mock[PersistenceStore[_, _, _]]
    mockedStore.sync() returns Future.successful(Done)
    mockedStore
  }

  "Migration" should {
    "be filterable by version" in {
      val migrate = migration()
      val all = migrate.migrations.filter(_._1 > StorageVersions(0, 0, 0)).sortBy(_._1)
      all should have size migrate.migrations.size.toLong

      val none = migrate.migrations.filter(_._1 > StorageVersions(Int.MaxValue, 0, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
      none should be('empty)

      val some = migrate.migrations.filter(_._1 < StorageVersions(0, 10, 0))
      some should have size 1
    }

    "migrate on an empty database will set the storage version" in {
      val mockedStore = mockPersistenceStore()
      val migrate = migration(persistenceStore = Option(mockedStore))

      mockedStore.storageVersion() returns Future.successful(None)
      mockedStore.setStorageVersion(any) returns Future.successful(Done)

      migrate.migrate()

      verify(mockedStore).sync()
      verify(mockedStore).storageVersion()
      verify(mockedStore).setStorageVersion(StorageVersions.current)
      noMoreInteractions(mockedStore)
    }

    "migrate on an empty legacy database will set the storage version" in {
      val legacyConfig = mock[LegacyStorageConfig]
      val mockedPersistentStore = mock[PersistentStore]
      mockedPersistentStore.sync() returns Future.successful(Done)
      mockedPersistentStore.load(Migration.StorageVersionName) returns Future.successful(None)
      mockedPersistentStore.create(eq(Migration.StorageVersionName), eq(StorageVersions.current.toByteArray.toIndexedSeq)) returns
        Future.successful(mock[PersistentEntity])

      legacyConfig.store returns mockedPersistentStore
      val migrate = migration(legacyConfig = Some(legacyConfig), persistenceStore = None)

      migrate.migrate()

      verify(mockedPersistentStore).sync()
      verify(mockedPersistentStore, times(2)).load(Migration.StorageVersionName)
      verify(mockedPersistentStore).create(Migration.StorageVersionName, StorageVersions.current.toByteArray.toIndexedSeq)
      noMoreInteractions(mockedPersistentStore)
    }

    "migrate on a database with the same version will do nothing" in {
      val mockedStore = mockPersistenceStore()
      val migrate = migration(persistenceStore = Option(mockedStore))

      val currentPersistenceVersion =
        StorageVersions.current.toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build()
      mockedStore.storageVersion() returns Future.successful(Some(currentPersistenceVersion))
      migrate.migrate()

      verify(mockedStore).sync()
      verify(mockedStore).storageVersion()
      noMoreInteractions(mockedStore)
    }

    "migrate on a legacy database with the same version will do nothing" in {
      val legacyConfig = mock[LegacyStorageConfig]
      val mockedPersistentStore = mock[PersistentStore]
      val currentVersionEntity = InMemoryEntity(Migration.StorageVersionName, 0, StorageVersions.current.toByteArray.toIndexedSeq)
      mockedPersistentStore.sync() returns Future.successful(Done)
      mockedPersistentStore.load(Migration.StorageVersionName) returns Future.successful(Some(currentVersionEntity))

      legacyConfig.store returns mockedPersistentStore

      val migrate = migration(legacyConfig = Some(legacyConfig), persistenceStore = None)

      migrate.migrate()
      verify(mockedPersistentStore).sync()
      verify(mockedPersistentStore).load(Migration.StorageVersionName)
      noMoreInteractions(mockedPersistentStore)
    }

    "migrate throws an error for early unsupported versions" in {
      val mockedStore = mockPersistenceStore()
      val migrate = migration(persistenceStore = Option(mockedStore))
      val minVersion = migrate.minSupportedStorageVersion

      Given("An unsupported storage version")
      val unsupportedVersion = StorageVersions(0, 2, 0)
      mockedStore.storageVersion() returns Future.successful(Some(unsupportedVersion))

      When("migrate is called for that version")
      val ex = intercept[RuntimeException] {
        migrate.migrate()
      }

      Then("Migration exits with a readable error message")
      ex.getMessage should equal (s"Migration from versions < ${minVersion.str} are not supported. Your version: ${unsupportedVersion.str}")
    }

    "migrate throws an error for versions > current" in {
      val mockedStore = mockPersistenceStore()
      val migrate = migration(persistenceStore = Option(mockedStore))
      val minVersion = migrate.minSupportedStorageVersion

      Given("An unsupported storage version")
      val unsupportedVersion = StorageVersions(Int.MaxValue, Int.MaxValue, Int.MaxValue, StorageVersion.StorageFormat.PERSISTENCE_STORE)
      mockedStore.storageVersion() returns Future.successful(Some(unsupportedVersion))

      When("migrate is called for that version")
      val ex = intercept[RuntimeException] {
        migrate.migrate()
      }

      Then("Migration exits with a readable error message")
      ex.getMessage should equal (s"Migration from ${unsupportedVersion.str} is not supported as it is newer than ${StorageVersions.current.str}.")
    }

    "migrate throws an error if using legacy store with a PersistenceStore version" in {
      val legacyConfig = mock[LegacyStorageConfig]
      val mockedPersistentStore = mock[PersistentStore]
      legacyConfig.store returns mockedPersistentStore

      Given("An unsupported storage version")
      val unsupportedVersion = StorageVersions.current.toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build()
      val entity = InMemoryEntity(Migration.StorageVersionName, 0, unsupportedVersion.toByteArray.toIndexedSeq)
      mockedPersistentStore.sync() returns Future.successful(Done)
      mockedPersistentStore.load(Migration.StorageVersionName) returns Future.successful(Some(entity))

      val migrate = migration(Some(legacyConfig), None)

      When("migrate is called for that version")
      val ex = intercept[RuntimeException] {
        migrate.migrate()
      }

      Then("Migration exits with a readable error message")
      ex.getMessage should equal ("Migration from this storage format back to the legacy storage format" +
        " is not supported.")
    }

    "initializes and closes the persistent store when performing a legacy migration" in {
      val legacyConfig = mock[LegacyStorageConfig]
      trait Store extends PersistentStore with PersistentStoreManagement
      val mockedPersistentStore = mock[Store]
      val currentVersionEntity = InMemoryEntity(Migration.StorageVersionName, 0, StorageVersions.current.toByteArray.toIndexedSeq)
      mockedPersistentStore.sync() returns Future.successful(Done)
      mockedPersistentStore.initialize() returns Future.successful(())
      mockedPersistentStore.close() returns Future.successful(Done)
      mockedPersistentStore.load(Migration.StorageVersionName) returns Future.successful(Some(currentVersionEntity))

      legacyConfig.store returns mockedPersistentStore
      val migrate = migration(legacyConfig = Some(legacyConfig))

      migrate.migrate()
      verify(mockedPersistentStore).sync()
      verify(mockedPersistentStore).initialize()
      verify(mockedPersistentStore).close()
      verify(mockedPersistentStore).load(Migration.StorageVersionName)
      noMoreInteractions(mockedPersistentStore)
    }

    "migrations are executed sequentially" in {
      val mockedStore = mockPersistenceStore()
      mockedStore.storageVersion() returns Future.successful(Some(StorageVersions(0, 8, 0)))
      mockedStore.versions(any)(any) returns Source.empty
      mockedStore.ids()(any) returns Source.empty
      mockedStore.get(any)(any, any) returns Future.successful(None)
      mockedStore.get(any, any)(any, any) returns Future.successful(None)
      mockedStore.store(any, any)(any, any) returns Future.successful(Done)
      mockedStore.store(any, any, any)(any, any) returns Future.successful(Done)
      mockedStore.setStorageVersion(any) returns Future.successful(Done)

      val migrate = migration(persistenceStore = Some(mockedStore))
      migrate.appRepository.all() returns Source(Nil)
      val result = migrate.migrate()
      result should be ('nonEmpty)
      result should be(migrate.migrations.drop(1).map(_._1))
    }
  }
}
