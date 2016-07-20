package mesosphere.marathon.core.storage.migration

// scalastyle:off
import akka.Done
import akka.stream.scaladsl.Source
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.LegacyStorageConfig
import mesosphere.marathon.core.storage.migration.StorageVersions._
import mesosphere.marathon.core.storage.repository.impl.legacy.store.{InMemoryEntity, PersistentStore, PersistentStoreManagement}
import mesosphere.marathon.core.storage.repository.{AppRepository, DeploymentRepository, GroupRepository, TaskFailureRepository, TaskRepository}
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.Mockito
import org.scalatest.GivenWhenThen

import scala.concurrent.Future
// scalastyle:on

class MigrationTest extends AkkaUnitTest with Mockito with GivenWhenThen {
  implicit private def metrics = new Metrics(new MetricRegistry)

  def migration(legacyConfig: Option[LegacyStorageConfig] = None,
                persistenceStore: Option[PersistenceStore[_, _, _]] = None,
                appRepository: AppRepository = mock[AppRepository],
                groupRepository: GroupRepository = mock[GroupRepository],
                deploymentRepository: DeploymentRepository = mock[DeploymentRepository],
                taskRepository: TaskRepository = mock[TaskRepository],
                taskFailureRepository: TaskFailureRepository = mock[TaskFailureRepository]): Migration = {
    new Migration(legacyConfig, persistenceStore, appRepository, groupRepository, deploymentRepository,
      taskRepository, taskFailureRepository)
  }

  val currentVersion = if (StorageVersions.current < StorageVersions(1, 3, 0)) {
    StorageVersions(1, 3, 0)
  } else {
    StorageVersions.current
  }

  "Migration" should {
    "be filterable by version" in {
      val migrate = migration()
      val all = migrate.migrations.filter(_._1 > StorageVersions(0, 0, 0)).sortBy(_._1)
      all should have size migrate.migrations.size.toLong

      val none = migrate.migrations.filter(_._1 > StorageVersions(Int.MaxValue, 0, 0))
      none should be('empty)

      val some = migrate.migrations.filter(_._1 < StorageVersions(0, 10, 0))
      some should have size 1
    }

    "migrate on an empty database will set the storage version" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val migrate = migration(persistenceStore = Option(mockedStore))

      mockedStore.storageVersion() returns Future.successful(None)
      migrate.migrate()

      verify(mockedStore).storageVersion()
      verify(mockedStore).setStorageVersion(StorageVersions.current)
      noMoreInteractions(mockedStore)
    }

    "migrate on a database with the same version will do nothing" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      val migrate = migration(persistenceStore = Option(mockedStore))

      mockedStore.storageVersion() returns Future.successful(Some(currentVersion))
      migrate.migrate()

      verify(mockedStore).storageVersion()
      noMoreInteractions(mockedStore)
    }

    "migrate on a legacy database with the same version will do nothing" in {
      val legacyConfig = mock[LegacyStorageConfig]
      val mockedPersistentStore = mock[PersistentStore]
      val currentVersionEntity = InMemoryEntity(Migration.StorageVersionName, 0, currentVersion.toByteArray)
      mockedPersistentStore.load(Migration.StorageVersionName) returns Future.successful(Some(currentVersionEntity))

      legacyConfig.store returns mockedPersistentStore

      val migrate = migration(legacyConfig = Some(legacyConfig))

      migrate.migrate()
      verify(mockedPersistentStore).load(Migration.StorageVersionName)
      noMoreInteractions(mockedPersistentStore)
    }

    "migrate throws an error for unsupported versions" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
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
      ex.getMessage should equal (s"Migration from versions < $minVersion is not supported. Your version: $unsupportedVersion")
    }

    "initializes and closes the persistent store when performing a legacy migration" in {
      val legacyConfig = mock[LegacyStorageConfig]
      trait Store extends PersistentStore with PersistentStoreManagement
      val mockedPersistentStore = mock[Store]
      val currentVersionEntity = InMemoryEntity(Migration.StorageVersionName, 0, currentVersion.toByteArray)
      mockedPersistentStore.initialize() returns Future.successful(())
      mockedPersistentStore.close() returns Future.successful(Done)
      mockedPersistentStore.load(Migration.StorageVersionName) returns Future.successful(Some(currentVersionEntity))

      legacyConfig.store returns mockedPersistentStore
      val migrate = migration(legacyConfig = Some(legacyConfig))

      migrate.migrate()
      verify(mockedPersistentStore).initialize()
      verify(mockedPersistentStore).close()
      verify(mockedPersistentStore).load(Migration.StorageVersionName)
      noMoreInteractions(mockedPersistentStore)
    }
    "migrations are executed sequentially" in {
      val mockedStore = mock[PersistenceStore[_, _, _]]
      mockedStore.storageVersion() returns Future.successful(Some(StorageVersions(0, 8, 0)))
      mockedStore.versions(any)(any) returns Source.empty
      mockedStore.ids()(any) returns Source.empty
      mockedStore.get(any)(any, any) returns Future.successful(None)
      mockedStore.get(any, any)(any, any) returns Future.successful(None)
      mockedStore.store(any, any)(any, any) returns Future.successful(Done)
      mockedStore.store(any, any, any)(any, any) returns Future.successful(Done)
      mockedStore.setStorageVersion(any) returns Future.successful(Done)

      val migrate = migration(persistenceStore = Some(mockedStore))
      val result = migrate.migrate()
      result should be ('nonEmpty)
      result should be(migrate.migrations.map(_._1).drop(1))
    }


  }
  /*

  test("migration is executed sequentially") {
    val f = new Fixture

    f.groupRepo.root() returns Future.successful(Group.empty)
    f.groupRepo.storeRoot(any, any, any) returns Future.successful(Done)
    f.store.load("internal:storage:version") returns Future.successful(None)
    f.store.create(any, any) returns Future.successful(mock[PersistentEntity])
    f.store.update(any) returns Future.successful(mock[PersistentEntity])
    f.store.allIds() returns Future.successful(Seq.empty)
    f.store.initialize() returns Future.successful(())
    f.store.load(any) returns Future.successful(None)
    f.appRepo.all() returns Source.empty
    f.appRepo.ids() returns Source.empty
    f.groupRepo.root() returns Future.successful(Group.empty)
    f.groupRepo.rootVersions() returns Source.empty[OffsetDateTime]

    val result = f.migration.applyMigrationSteps(StorageVersions(0, 8, 0)).futureValue
    result should not be 'empty
    result should be(f.migration.migrations.map(_._1).drop(1))
  }

  test("applyMigrationSteps throws an error for unsupported versions") {
    val f = new Fixture
    val minVersion = f.migration.minSupportedStorageVersion

    Given("An unsupported storage version")
    val unsupportedVersion = StorageVersions(0, 2, 0)

    When("applyMigrationSteps is called for that version")
    val ex = intercept[RuntimeException] {
      f.migration.applyMigrationSteps(unsupportedVersion)
    }

    Then("Migration exits with a readable error message")
    ex.getMessage should equal (s"Migration from versions < $minVersion is not supported. Your version: $unsupportedVersion")
  }

  test("migrate() from unsupported version exits with a readable error") {
    val f = new Fixture
    val minVersion = f.migration.minSupportedStorageVersion

    f.groupRepo.root() returns Future.successful(Group.empty)
    f.groupRepo.storeRoot(any, any, any) returns Future.successful(Done)

    f.store.load("internal:storage:version") returns Future.successful(Some(InMemoryEntity(
      id = "internal:storage:version", version = 0, bytes = minVersion.toByteArray)))
    f.store.initialize() returns Future.successful(())

    Given("An unsupported storage version")
    val unsupportedVersion = StorageVersions(0, 2, 0)
    f.store.load("internal:storage:version") returns Future.successful(Some(InMemoryEntity(
      id = "internal:storage:version", version = 0, bytes = unsupportedVersion.toByteArray)))

    When("A migration is approached for that version")
    val ex = intercept[RuntimeException] {
      f.migration.migrate()
    }

    Then("Migration exits with a readable error message")
    ex.getMessage should equal (s"Migration from versions < $minVersion is not supported. Your version: $unsupportedVersion")
  }

  class Fixture {
    trait StoreWithManagement extends PersistentStore with PersistentStoreManagement


    val metrics = new Metrics(new MetricRegistry)
    val store = mock[StoreWithManagement]
    val appRepo = mock[AppEntityRepository]
    val groupRepo = mock[GroupEntityRepository]
    val config = mock[MarathonConf]
    val deploymentRepo = new DeploymentEntityRepository(
      new MarathonStore[DeploymentPlan](
        store = store,
        metrics = metrics,
        newState = () => DeploymentPlan.empty,
        prefix = "deployment:"))(metrics = metrics)
    val taskRepo = new TaskEntityRepository(
      new MarathonStore[MarathonTaskState](
        store = store,
        metrics = metrics,
        newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()),
        prefix = "task:")
    )(metrics = metrics)
    val migration = new Migration(
      store,
      appRepo,
      groupRepo,
      taskRepo,
      deploymentRepo,
      config,
      new Metrics(new MetricRegistry)
    )
  }
  */
}
