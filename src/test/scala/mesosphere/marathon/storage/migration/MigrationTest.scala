package mesosphere.marathon
package storage.migration

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.backup.PersistentStoreBackup
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.state.RootGroup
import mesosphere.marathon.storage.{ InMem, StorageConfig }
import mesosphere.marathon.storage.migration.StorageVersions._
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.test.{ Mockito, SettableClock, SimulatedScheduler }
import org.scalatest.GivenWhenThen
import Migration.MigrationAction
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ Future, Promise }

class MigrationTest extends AkkaUnitTest with Mockito with GivenWhenThen with Eventually {

  class Fixture(
      persistenceStore: PersistenceStore[_, _, _] = {
        val store = new InMemoryPersistenceStore()
        store.markOpen()
        store
      },
      fakeMigrations: List[MigrationAction] = List.empty) {
    private val appRepository: AppRepository = mock[AppRepository]
    private val podRepository: PodRepository = mock[PodRepository]
    private val groupRepository: GroupRepository = mock[GroupRepository]
    private val deploymentRepository: DeploymentRepository = mock[DeploymentRepository]
    private val instanceRepository: InstanceRepository = mock[InstanceRepository]
    private val taskFailureRepository: TaskFailureRepository = mock[TaskFailureRepository]
    private val frameworkIdRepository: FrameworkIdRepository = mock[FrameworkIdRepository]
    private val configurationRepository: RuntimeConfigurationRepository = mock[RuntimeConfigurationRepository]
    private val backup: PersistentStoreBackup = mock[PersistentStoreBackup]
    private val serviceDefinitionRepository: ServiceDefinitionRepository = mock[ServiceDefinitionRepository]
    private val config: StorageConfig = InMem(1, Set.empty, None, None)

    // assume no runtime config is stored in repository
    configurationRepository.get() returns Future.successful(None)
    configurationRepository.store(any) returns Future.successful(Done)
    val clock = SettableClock.ofNow()
    implicit val scheduler = new SimulatedScheduler(clock)
    val notificationCounter = new AtomicInteger(0)
    val migration = new Migration(Set.empty, None, "bridge-name", persistenceStore, appRepository, podRepository, groupRepository, deploymentRepository,
      instanceRepository, taskFailureRepository, frameworkIdRepository,
      serviceDefinitionRepository, configurationRepository, backup, config) {

      override protected def notifyMigrationInProgress(from: StorageVersion, migrateVersion: StorageVersion): Unit =
        notificationCounter.incrementAndGet()

      override def migrations: List[(StorageVersion, () => Future[Any])] = if (fakeMigrations.nonEmpty) {
        fakeMigrations
      } else {
        super.migrations
      }
    }
  }

  val currentVersion: StorageVersion = StorageVersions.current

  "Migration" should {
    "be filterable by version" in {
      val f = new Fixture

      val migrate = f.migration
      val all = migrate.migrations.filter(_._1 > StorageVersions(0, 0, 0)).sortBy(_._1)
      all should have size migrate.migrations.size.toLong

      val none = migrate.migrations.filter(_._1 > StorageVersions(Int.MaxValue, 0, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
      none should be('empty)

      val some = migrate.migrations.filter(_._1 < StorageVersions(1, 5, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
      some should have size 2 // we do have two migrations now, 1.4.2 and 1.4.6
    }

    "migrate on an empty database will set the storage version" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]

      val f = new Fixture(mockedStore)

      val migrate = f.migration

      mockedStore.isOpen returns true
      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(None)
      mockedStore.setStorageVersion(any) returns Future.successful(Done)
      mockedStore.endMigration() returns Future.successful(Done)

      migrate.migrate()

      verify(mockedStore).startMigration()
      verify(mockedStore).storageVersion()
      verify(mockedStore).setStorageVersion(StorageVersions.current)
      verify(mockedStore).endMigration()
      noMoreInteractions(mockedStore)
    }

    "migrate on a database with the same version will do nothing" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val f = new Fixture(mockedStore)

      val migrate = f.migration

      mockedStore.isOpen returns true
      val currentPersistenceVersion =
        StorageVersions.current.toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build()

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(Some(currentPersistenceVersion))
      mockedStore.endMigration() returns Future.successful(Done)

      migrate.migrate()

      verify(mockedStore).startMigration()
      verify(mockedStore).storageVersion()
      verify(mockedStore).endMigration()
      noMoreInteractions(mockedStore)
    }

    "migrate throws an error for early unsupported versions" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val f = new Fixture(mockedStore)

      val migrate = f.migration
      val minVersion = migrate.minSupportedStorageVersion

      Given("An unsupported storage version")
      val unsupportedVersion = StorageVersions(0, 2, 0)

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(Some(unsupportedVersion))

      When("migrate is called for that version")
      val ex = intercept[RuntimeException] {
        migrate.migrate()
      }

      Then("Migration exits with a readable error message")
      ex.getMessage should equal (s"Migration from versions < ${minVersion.str} are not supported. Your version: ${unsupportedVersion.str}")
    }

    "migrate throws an error for versions > current" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val f = new Fixture(mockedStore)

      val migrate = f.migration

      Given("An unsupported storage version")
      val unsupportedVersion = StorageVersions(Int.MaxValue, Int.MaxValue, Int.MaxValue, StorageVersion.StorageFormat.PERSISTENCE_STORE)

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(Some(unsupportedVersion))

      When("migrate is called for that version")
      val ex = intercept[RuntimeException] {
        migrate.migrate()
      }

      Then("Migration exits with a readable error message")
      ex.getMessage should equal (s"Migration from ${unsupportedVersion.str} is not supported as it is newer than ${StorageVersions.current.str}.")
    }

    "migrations are executed sequentially" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(Some(StorageVersions(1, 4, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)))
      mockedStore.versions(any)(any) returns Source.empty
      mockedStore.ids()(any) returns Source.empty
      mockedStore.get(any)(any, any) returns Future.successful(None)
      mockedStore.getVersions(any)(any, any) returns Source.empty
      mockedStore.get(any, any)(any, any) returns Future.successful(None)
      mockedStore.store(any, any)(any, any) returns Future.successful(Done)
      mockedStore.store(any, any, any)(any, any) returns Future.successful(Done)
      mockedStore.setStorageVersion(any) returns Future.successful(Done)
      mockedStore.endMigration() returns Future.successful(Done)

      val f = new Fixture(mockedStore)

      val migrate = f.migration
      migrate.appRepository.all() returns Source.empty
      migrate.groupRepository.rootVersions() returns Source.empty
      migrate.groupRepository.root() returns Future.successful(RootGroup.empty)
      migrate.groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)
      migrate.serviceDefinitionRepo.getVersions(any) returns Source.empty
      val result = migrate.migrate()
      result should be ('nonEmpty)
      result should contain theSameElementsInOrderAs migrate.migrations.map(_._1)
    }

    "log periodic messages if migration takes more time than usual" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val started = Promise[Done]
      val migrationDone = Promise[Done]
      val version = StorageVersions(1, 4, 2, StorageVersion.StorageFormat.PERSISTENCE_STORE)

      val migration = List(
        version -> { () =>
          started.success(Done)
          migrationDone.future
        }
      )
      val f = new Fixture(mockedStore, migration)

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(
        Some(StorageVersions(1, 4, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
      )
      mockedStore.setStorageVersion(any) returns Future.successful(Done)
      mockedStore.endMigration() returns Future.successful(Done)

      val result = f.migration.migrateAsync()

      started.future.futureValue shouldBe Done
      (1 to 3) foreach { _ =>
        f.clock += Migration.statusLoggingInterval
      }
      eventually {
        f.notificationCounter.get shouldBe 3
      }

      migrationDone.success(Done)

      eventually { f.scheduler.taskCount shouldBe 0 }
      result.futureValue shouldBe List(version)
    }

    "throw an error if migration is in progress already" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val f = new Fixture(mockedStore)
      mockedStore.startMigration() throws new StoreCommandFailedException("Migration is already in progress")

      val migrate = f.migration

      val thrown = the[StoreCommandFailedException] thrownBy migrate.migrate()
      thrown.getMessage should equal("Migration is already in progress")

      verify(mockedStore).startMigration()
      noMoreInteractions(mockedStore)
    }

    "throw an error and remove a migration flag if migration gets cancelled" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val version = StorageVersions(1, 4, 2, StorageVersion.StorageFormat.PERSISTENCE_STORE)
      val failingMigration: MigrationAction = (version,
        () => Future.failed(MigrationCancelledException("Migration cancelled", new Exception("Failed to do something"))))
      val f = new Fixture(mockedStore, List(failingMigration))

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(
        Some(StorageVersions(1, 4, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)))
      mockedStore.endMigration() returns Future.successful(Done)

      val migration = f.migration

      val thrown = the[MigrationFailedException] thrownBy migration.migrate()
      thrown.getMessage should equal("Migration cancelled")
      thrown.getCause shouldBe a[Exception]
      thrown.getCause.getMessage should equal("Failed to do something")

      verify(mockedStore).startMigration()
      verify(mockedStore).storageVersion()
      verify(mockedStore).endMigration()
      noMoreInteractions(mockedStore)
    }
  }
}
