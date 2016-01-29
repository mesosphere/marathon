package mesosphere.marathon.state

import java.io.StreamCorruptedException
import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.FutureTestSupport._
import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.util.state.FrameworkId
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.{ GivenWhenThen, Matchers }

class MigrationTo0_13Test extends MarathonSpec with GivenWhenThen with Matchers {

  test("migrate tasks in zk") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = MarathonTestHelper.dummyTaskProto(appId)
    val task2 = MarathonTestHelper.dummyTaskProto(appId)
    f.legacyTaskStore.store(appId, task1).futureValue
    f.legacyTaskStore.store(appId, task2).futureValue
    val names = f.entityStore.names().futureValue
    names should have size 2
    names should contain (appId.safePath + ":" + task1.getId)
    names should contain (appId.safePath + ":" + task2.getId)

    When("we run the migration")
    f.migration.migrateTasks().futureValue

    Then("the tasks are stored in paths without duplicated appId")
    val taskKeys = f.taskRepo.tasksKeys(appId).futureValue

    taskKeys should have size 2
    taskKeys should contain (task1.getId)
    taskKeys should not contain f.legacyStoreKey(appId, task1.getId)
    taskKeys should contain (task2.getId)
    taskKeys should not contain f.legacyStoreKey(appId, task2.getId)
  }

  test("Migrating a migrated task throws an Exception") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = MarathonTestHelper.dummyTaskProto(appId)
    f.legacyTaskStore.store(appId, task1).futureValue

    When("we migrate that task")
    f.migration.migrateKey(f.legacyStoreKey(appId, task1.getId)).futureValue

    Then("migrating it again will throw")
    val result = f.migration.migrateKey(task1.getId).failed.futureValue
    result.isInstanceOf[StreamCorruptedException]
    result.getMessage.contains("invalid stream header")
  }

  test("Already migrated tasks will be excluded from a subsequent migration attempt") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = MarathonTestHelper.dummyTaskProto(appId)
    f.legacyTaskStore.store(appId, task1).futureValue
    val names = f.entityStore.names().futureValue
    names should have size 1
    names should contain (appId.safePath + ":" + task1.getId)

    When("we run the migration")
    f.migration.migrateTasks().futureValue

    Then("the tasks are stored in paths without duplicated appId")
    val taskKeys1 = f.taskRepo.tasksKeys(appId).futureValue

    taskKeys1 should have size 1

    When("we add another task in old format")
    val task2 = MarathonTestHelper.dummyTaskProto(appId)
    f.legacyTaskStore.store(appId, task2).futureValue
    f.entityStore.names().futureValue should contain (appId.safePath + ":" + task2.getId)

    And("we run the migration again")
    f.migration.migrateTasks().futureValue

    Then("Only the second task is considered and the first one does not crash the migration")
    val taskKeys2 = f.taskRepo.tasksKeys(appId).futureValue
    taskKeys2 should have size 2
    taskKeys2 should contain (task1.getId)
    taskKeys2 should not contain f.legacyStoreKey(appId, task1.getId)
    taskKeys2 should contain (task2.getId)
    taskKeys2 should not contain f.legacyStoreKey(appId, task2.getId)
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
    f.migration.renameFrameworkId().futureValue

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
    f.migration.renameFrameworkId().futureValue

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
    f.migration.renameFrameworkId().futureValue

    Then("Nothing should have changed")
    val newNames = f.frameworkIdStore.names().futureValue
    newNames.size should be (1)
    newNames should contain (newName)
    f.frameworkIdStore.fetch(newName).futureValue should be (Some(frameworkId))
  }

  class Fixture {
    lazy val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())
    lazy val state = new InMemoryStore
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val legacyTaskStore = new LegacyTaskStore(state)
    lazy val entityStore = new MarathonStore[MarathonTaskState](
      store = state,
      metrics = metrics,
      newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()),
      prefix = TaskRepository.storePrefix)
    lazy val taskRepo = {
      val metrics = new Metrics(new MetricRegistry)
      new TaskRepository(entityStore, metrics)
    }
    lazy val frameworkIdStore = new MarathonStore[FrameworkId](
      store = state,
      metrics = metrics,
      newState = () => new FrameworkId(UUID.randomUUID().toString),
      prefix = "" // don't set the prefix so we don't have to use PersistentStore for testing
    )

    lazy val migration = new MigrationTo0_13(taskRepo, state)

    def legacyStoreKey(appId: PathId, taskId: String): String = appId.safePath + ":" + taskId
  }

  object FrameworkIdValues {
    val oldName = "frameworkId"
    val newName = "framework:id"
  }

}

import java.io._

import mesosphere.util.state.{ PersistentEntity, PersistentStore }

import scala.concurrent.Future

private[state] class LegacyTaskStore(store: PersistentStore) {

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
