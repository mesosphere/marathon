package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.task.tracker.impl.{ MarathonTaskStatusSerializer, TaskSerializer }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ GivenWhenThen, Matchers }
import mesosphere.marathon.state.PathId.StringPathId

import scala.concurrent.Future
import mesosphere.marathon.Protos
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import scala.concurrent.ExecutionContext.Implicits.global

class MigrationTo1_2Test extends MarathonSpec with GivenWhenThen with Matchers {
  import mesosphere.FutureTestSupport._

  class Fixture {
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val store = new InMemoryStore()
    lazy val deploymentStore = new MarathonStore[DeploymentPlan](
      store = store,
      metrics = metrics,
      newState = () => DeploymentPlan.empty,
      prefix = "deployment:"
    )
    lazy val taskStore = new MarathonStore[MarathonTaskState](
      store = store,
      metrics = metrics,
      newState = () => MarathonTaskState(MarathonTestHelper.createdMarathonTask),
      prefix = "task:")
    lazy val taskRepo = new TaskRepository(taskStore, metrics)
    lazy val deploymentRepo = new DeploymentRepository(deploymentStore, metrics)
    lazy val migration = new MigrationTo1_2(deploymentRepo, taskRepo)
  }

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(1, Seconds))

  val f = new Fixture

  test("should remove deployment version nodes, but keep deployment nodes") {
    Given("some deployment version nodes, a proper deployment node and an unrelated node")
    val f = new Fixture

    f.store.create("deployment:265fe17c-2979-4ab6-b906-9c2b34f9c429:2016-06-23T22:16:03.880Z", IndexedSeq.empty)
    f.store.create("deployment:42c6b840-5a4b-4110-a7d9-d4835f7499b9:2016-06-13T18:47:15.862Z", IndexedSeq.empty)
    f.store.create("deployment:fcabfa75-7756-4bc8-94b3-c9d5b2abd38c", IndexedSeq.empty)
    f.store.create("foo:bar", IndexedSeq.empty)

    When("migrating")
    f.migration.migrate().futureValue

    Then("the deployment version nodes are removed, all other nodes are kept")
    val nodeNames: Seq[String] = f.store.allIds().futureValue
    nodeNames should contain theSameElementsAs Seq("deployment:fcabfa75-7756-4bc8-94b3-c9d5b2abd38c", "foo:bar")
  }

  test("should migrate tasks and add calculated MarathonTaskStatus to stored tasks") {
    Given("some tasks without MarathonTaskStatus")
    val f = new Fixture

    def loadTask(id: String): Future[Protos.MarathonTask] = f.taskRepo.task(id).map {
      case Some(entity) => entity
      case None => fail("Entity id was found with allIds(), but no entity could be loaded with task(id).")
    }

    f.taskRepo.store(TaskSerializer.toProto(MarathonTestHelper.minimalRunning("/running1".toPath, null)))
    f.taskRepo.store(TaskSerializer.toProto(MarathonTestHelper.minimalRunning("/running2".toPath, null)))
    f.taskRepo.store(TaskSerializer.toProto(MarathonTestHelper.minimalRunning("/running3".toPath, null)))
    f.taskRepo.store(TaskSerializer.toProto(MarathonTestHelper.minimalUnreachableTask("/unreachable1".toPath, null)))
    f.taskRepo.store(TaskSerializer.toProto(MarathonTestHelper.mininimalLostTask("/lost1".toPath, null)))

    When("migrating")
    f.migration.migrate().futureValue

    Then("the tasks should all have a MarathonTaskStatus according their initial mesos task status")
    val storedTasks = for {
      ids <- f.taskRepo.allIds()
      tasks <- {
        Future.sequence(ids.map(loadTask))
      }
    } yield tasks

    storedTasks.futureValue.foreach {
      task =>
        task.getMarathonTaskStatus should not be null

        val serializedTask = TaskSerializer.fromProto(task)
        val expectedStatus = MarathonTaskStatus(serializedTask.mesosStatus.getOrElse(fail("Task has no mesos task status")))
        val currentStatus = MarathonTaskStatusSerializer.fromProto(task.getMarathonTaskStatus)

        currentStatus should be equals expectedStatus
    }
  }

}
