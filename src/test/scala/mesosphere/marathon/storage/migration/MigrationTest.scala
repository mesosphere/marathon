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
import mesosphere.marathon.storage.{InMem, StorageConfig}
import mesosphere.marathon.storage.migration.StorageVersions._
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.test.{Mockito, SettableClock, SimulatedScheduler}
import org.scalatest.GivenWhenThen
import Migration.MigrationAction
import akka.stream.Materializer
import mesosphere.marathon.metrics.dummy.DummyMetrics
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ExecutionContext, Future, Promise}

class MigrationTest extends AkkaUnitTest with Mockito with GivenWhenThen with Eventually {

  class Fixture(
      persistenceStore: PersistenceStore[_, _, _] = {
        val store = new InMemoryPersistenceStore(DummyMetrics)
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
    private val config: StorageConfig = InMem(1, 32, Set.empty, None, None, 1000)

    // assume no runtime config is stored in repository
    configurationRepository.get() returns Future.successful(None)
    configurationRepository.store(any) returns Future.successful(Done)
    val clock = SettableClock.ofNow()
    implicit val scheduler = new SimulatedScheduler(clock)
    val notificationCounter = new AtomicInteger(0)
    val migrationSteps = if (fakeMigrations.nonEmpty) fakeMigrations else Migration.steps
    val migration = new Migration(Set.empty, None, "bridge-name", persistenceStore, appRepository, podRepository, groupRepository, deploymentRepository,
      instanceRepository, taskFailureRepository, frameworkIdRepository,
      serviceDefinitionRepository, configurationRepository, backup, config, migrationSteps) {

      override protected def notifyMigrationInProgress(from: StorageVersion, migrateVersion: StorageVersion): Unit =
        notificationCounter.incrementAndGet()

    }
  }

  val currentVersion: StorageVersion = StorageVersions(Migration.steps)

  "Migration" should {
    "be filterable by version" in {
      val f = new Fixture

      val migrate = f.migration
      val all = migrate.steps.filter(_._1 > StorageVersions(0, 0, 0)).sortBy(_._1)
      all should have size migrate.steps.size.toLong

      val none = migrate.steps.filter(_._1 > StorageVersions(Int.MaxValue, 0, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
      none should be('empty)

      val some = migrate.steps.filter(_._1 <= StorageVersions(18, 0, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
      some should have size 2 // we do have two migrations now, 17 and 18
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
      verify(mockedStore).setStorageVersion(StorageVersions(Migration.steps))
      verify(mockedStore).endMigration()
      noMoreInteractions(mockedStore)
    }

    "migrate on a database with the same version will do nothing" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val f = new Fixture(mockedStore)

      val migrate = f.migration

      mockedStore.isOpen returns true
      val currentPersistenceVersion =
        StorageVersions(Migration.steps).toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build()
      mockedStore.storageVersion() returns Future.successful(Some(currentPersistenceVersion))

      migrate.migrate()

      verify(mockedStore).storageVersion()
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
      ex.getMessage should equal (s"Migration from ${unsupportedVersion.str} is not supported as it is newer than ${StorageVersions(Migration.steps).str}.")
    }

    "migrations are executed sequentially" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(Some(StorageVersions(1, 6, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)))
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
      result should contain theSameElementsInOrderAs migrate.steps.map(_._1)
    }

    "log periodic messages if migration takes more time than usual" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val started = Promise[Done]
      val migrationDone = Promise[Done]
      val version = StorageVersions(17, 0, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)

      val migration = List(
        version -> { _: Migration =>
          new MigrationStep {
            override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
              started.success(Done)
              migrationDone.future
            }
          }
        }
      )
      val f = new Fixture(mockedStore, migration)

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(
        Some(StorageVersions(1, 6, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE))
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
      mockedStore.storageVersion() returns Future.successful(Some(StorageVersions(1, 6, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)))

      val migrate = f.migration

      val thrown = the[MigrationFailedException] thrownBy migrate.migrate()
      thrown.getMessage should equal("Migration Failed: Migration is already in progress")
      thrown.getCause shouldBe a[StoreCommandFailedException]

      verify(mockedStore).startMigration()
      verify(mockedStore).storageVersion()
      noMoreInteractions(mockedStore)
    }

    "throw an error and remove a migration flag if migration gets cancelled" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val version = StorageVersions(17, 0, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)
      val failingMigration: MigrationAction = (version,
        { _: Migration =>
          new MigrationStep {
            override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
              Future.failed(MigrationCancelledException("Migration cancelled", new Exception("Failed to do something")))
            }
          }
        })
      val f = new Fixture(mockedStore, List(failingMigration))

      mockedStore.startMigration() returns Future.successful(Done)
      mockedStore.storageVersion() returns Future.successful(
        Some(StorageVersions(1, 6, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)))
      mockedStore.endMigration() returns Future.successful(Done)

      val migration = f.migration

      val thrown = the[MigrationCancelledException] thrownBy migration.migrate()
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
