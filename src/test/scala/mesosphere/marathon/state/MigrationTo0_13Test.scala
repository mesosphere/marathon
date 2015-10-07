package mesosphere.marathon.state

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.metrics.Metrics
import mesosphere.util.state.memory.InMemoryStore
import org.mockito.Mockito.spy
import org.scalatest.{ GivenWhenThen, Matchers }
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.FutureTestSupport._

class MigrationTo0_13Test extends MarathonSpec with GivenWhenThen with Matchers {

  test("migrate tasks in zk") {
    val f = new Fixture
    Given("some tasks that are stored in old path style")
    val appId = "/test/app1".toRootPath
    val task1 = dummyTask(appId)
    val task2 = dummyTask(appId)
    f.entityStore.store(f.legacyStoreKey(appId, task1.getId), MarathonTaskState(task1)).futureValue
    f.entityStore.store(f.legacyStoreKey(appId, task2.getId), MarathonTaskState(task2)).futureValue
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
    lazy val state = spy(new InMemoryStore)
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val entityStore = new MarathonStore[MarathonTaskState](
      store = state,
      metrics = metrics,
      newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()),
      prefix = TaskRepository.storePrefix)
    lazy val taskRepo = {
      val metrics = new Metrics(new MetricRegistry)
      new TaskRepository(entityStore, metrics)
    }
    lazy val migration = new MigrationTo0_13(taskRepo)

    def legacyStoreKey(appId: PathId, taskId: String): String = appId.safePath + ":" + taskId
  }

}
