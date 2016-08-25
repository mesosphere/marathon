package mesosphere.marathon.storage.migration.legacy.legacy

import java.io.StreamCorruptedException
import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.FutureTestSupport._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{ MarathonTaskState, PathId }
import mesosphere.marathon.storage.LegacyInMemConfig
import mesosphere.marathon.storage.repository.TaskRepository
import mesosphere.marathon.storage.repository.legacy.store.{ MarathonStore, PersistentEntity, PersistentStore }
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.util.state.FrameworkId
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.ExecutionContext

class MigrationTo0_13Test extends MarathonSpec with MarathonActorSupport with GivenWhenThen with Matchers {

  test("migrate tasks in zk") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = MarathonTestHelper.mininimalTask(appId)
    val task2 = MarathonTestHelper.mininimalTask(appId)
    val task1Proto = TaskSerializer.toProto(task1)
    val task2Proto = TaskSerializer.toProto(task2)
    f.legacyTaskStore.store(appId, task1Proto).futureValue
    f.legacyTaskStore.store(appId, task2Proto).futureValue
    val names = f.entityStore.names().futureValue
    names should have size 2
    names should contain (appId.safePath + ":" + task1Proto.getId)
    names should contain (appId.safePath + ":" + task2Proto.getId)

    When("we run the migration")
    f.migration.migrateTasks(f.state, f.taskRepo).futureValue

    Then("the tasks are stored in paths without duplicated appId")
    val taskKeys = f.taskRepo.tasks(appId).runWith(Sink.seq).futureValue

    taskKeys should contain theSameElementsAs Seq(task1.taskId, task2.taskId)
    taskKeys.map(_.toString) should not contain f.legacyStoreKey(appId, task1Proto.getId)
    taskKeys.map(_.toString) should not contain f.legacyStoreKey(appId, task2Proto.getId)
  }

  test("Migrating a migrated task throws an Exception") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = TaskSerializer.toProto(MarathonTestHelper.mininimalTask(appId))
    f.legacyTaskStore.store(appId, task1).futureValue

    When("we migrate that task")
    f.migration.migrateKey(f.state, f.taskRepo, f.legacyStoreKey(appId, task1.getId)).futureValue

    Then("migrating it again will throw")
    val result = f.migration.migrateKey(f.state, f.taskRepo, task1.getId).failed.futureValue
    result.isInstanceOf[StreamCorruptedException]
    result.getMessage.contains("invalid stream header")
  }

  test("Already migrated tasks will be excluded from a subsequent migration attempt") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = MarathonTestHelper.mininimalTask(appId)
    val task1Proto = TaskSerializer.toProto(task1)
    f.legacyTaskStore.store(appId, task1Proto).futureValue
    val names = f.entityStore.names().futureValue
    names should have size 1
    names should contain (appId.safePath + ":" + task1Proto.getId)

    When("we run the migration")
    f.migration.migrateTasks(f.state, f.taskRepo).futureValue

    Then("the tasks are stored in paths without duplicated appId")
    val taskKeys1 = f.taskRepo.tasks(appId).runWith(Sink.seq).futureValue

    taskKeys1 should have size 1

    When("we add another task in old format")
    val task2 = MarathonTestHelper.mininimalTask(appId)
    val task2Proto = TaskSerializer.toProto(task2)
    f.legacyTaskStore.store(appId, task2Proto).futureValue
    f.entityStore.names().futureValue should contain (appId.safePath + ":" + task2Proto.getId)

    And("we run the migration again")
    f.migration.migrateTasks(f.state, f.taskRepo).futureValue

    Then("Only the second task is considered and the first one does not crash the migration")
    val taskKeys2 = f.taskRepo.tasks(appId).runWith(Sink.seq).futureValue
    taskKeys2 should contain theSameElementsAs Seq(task1.taskId, task2.taskId)
    taskKeys2.map(_.toString) should not contain f.legacyStoreKey(appId, task1Proto.getId)
    taskKeys2.map(_.toString) should not contain f.legacyStoreKey(appId, task2Proto.getId)
  }

  test("migrating frameworkId to framework:id") {
    import FrameworkIdValues._
    val f = new Fixture
    val frameworkId = FrameworkId("myFramework")

    Given("a frameworkId under the old key")
    f.frameworkIdStore.store(oldName, frameworkId).futureValue
    f.frameworkIdStore.names().futureValue should contain (oldName)
    f.frameworkIdStore.fetch(oldName).futureValue should be (Some(frameworkId))

    When("we run the migration")
    f.migration.renameFrameworkId(f.state).futureValue

    Then("The old key should be deleted")
    val namesAfterMigration = f.frameworkIdStore.names().futureValue
    namesAfterMigration should not contain oldName

    And("The new key should be present and contain the correct value")
    namesAfterMigration should contain (newName)
    f.frameworkIdStore.fetch(newName).futureValue should be (Some(frameworkId))
  }

  test("migrating frameworkId does nothing for non-existent node") {
    val f = new Fixture

    Given("a non-existing frameworkId")
    f.frameworkIdStore.names().futureValue should be (empty)

    When("we run the migration")
    f.migration.renameFrameworkId(f.state).futureValue

    Then("Nothing should have happened")
    f.frameworkIdStore.names().futureValue should be (empty)
  }

  test("migrating frameworkId is skipped if framework:id already exists") {
    import FrameworkIdValues._
    val f = new Fixture
    val frameworkId = FrameworkId("myFramework")

    Given("An existing framework:id")
    f.frameworkIdStore.store(newName, frameworkId).futureValue
    val names = f.frameworkIdStore.names().futureValue
    names.size should be (1)
    names should contain (newName)

    When("we run the migration")
    f.migration.renameFrameworkId(f.state).futureValue

    Then("Nothing should have changed")
    val newNames = f.frameworkIdStore.names().futureValue
    newNames.size should be (1)
    newNames should contain (newName)
    f.frameworkIdStore.fetch(newName).futureValue should be (Some(frameworkId))
  }

  class Fixture {
    implicit val ctx = ExecutionContext.global
    lazy val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())
    val maxVersions = 25
    lazy val config = LegacyInMemConfig(maxVersions)
    lazy val state = config.store
    implicit lazy val metrics = new Metrics(new MetricRegistry)
    lazy val legacyTaskStore = new LegacyTaskStore(state)
    lazy val taskRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])
    lazy val entityStore = taskRepo.store
    lazy val frameworkIdStore = new MarathonStore[FrameworkId](
      store = state,
      metrics = metrics,
      newState = () => new FrameworkId(UUID.randomUUID().toString),
      prefix = "" // don't set the prefix so we don't have to use PersistentStore for testing
    )

    lazy val migration = new MigrationTo0_13(Some(config))

    def legacyStoreKey(appId: PathId, taskId: String): String = appId.safePath + ":" + taskId
  }

  object FrameworkIdValues {
    val oldName = "frameworkId"
    val newName = "framework:id"
  }
}

import java.io._

import scala.concurrent.Future

private[legacy] class LegacyTaskStore(store: PersistentStore) {

  val PREFIX = "task:"
  val ID_DELIMITER = ":"

  private[this] def getKey(appId: PathId, taskId: String): String = {
    PREFIX + appId.safePath + ID_DELIMITER + taskId
  }

  private[this] def serialize(task: MarathonTask, sink: ObjectOutputStream): Unit = {
    val size = task.getSerializedSize
    sink.writeInt(size)
    sink.write(task.toByteArray)
    sink.flush()
  }

  def store(appId: PathId, task: MarathonTask): Future[PersistentEntity] = {
    val byteStream = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(byteStream)
    serialize(task, output)
    val bytes = byteStream.toByteArray
    val key: String = getKey(appId, task.getId)
    store.create(key, bytes)
  }

}

