package mesosphere.marathon.state

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.FutureTestSupport._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.{ GivenWhenThen, Matchers }

class MigrationTo0_13Test extends MarathonSpec with GivenWhenThen with Matchers {

  test("migrate tasks in zk") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = dummyTask(appId)
    val task2 = dummyTask(appId)
    f.legacyTaskStore.store(appId, task1).futureValue
    f.legacyTaskStore.store(appId, task2).futureValue
    val names = f.entityStore.names().futureValue
    names should not be empty
    names should contain (appId.safePath + ":" + task1.getId)
    names should contain (appId.safePath + ":" + task2.getId)

    When("we run the migration")
    f.migration.migrateTasks().futureValue

    Then("the tasks are stored in paths without duplicated appId")
    val taskKeys = f.taskRepo.tasksKeys(appId).futureValue

    taskKeys should not be empty
    taskKeys.size should be (2)
    taskKeys should contain (task1.getId)
    taskKeys should not contain f.legacyStoreKey(appId, task1.getId)
    taskKeys should contain (task2.getId)
    taskKeys should not contain f.legacyStoreKey(appId, task2.getId)
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
    lazy val migration = new MigrationTo0_13(taskRepo, state)

    def legacyStoreKey(appId: PathId, taskId: String): String = appId.safePath + ":" + taskId
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
