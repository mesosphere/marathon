package mesosphere.marathon.state

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.task.tracker.impl.{ MarathonTaskStatusSerializer, TaskSerializer }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.core.task.Task
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ GivenWhenThen, Matchers }
import mesosphere.marathon.state.PathId.StringPathId

import scala.concurrent.Future
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import org.apache._
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import org.apache.mesos.Protos.TaskStatus

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
      newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()),
      prefix = "task:")
    lazy val taskRepo = new TaskRepository(taskStore, metrics)
    lazy val deploymentRepo = new DeploymentRepository(deploymentStore, metrics)
    lazy val migration = new MigrationTo1_2(deploymentRepo, taskRepo)

    def create(key: String, bytes: IndexedSeq[Byte]): Unit = {
      store.create(key, bytes).futureValue
    }
    def store(key: String, state: MarathonTaskState): Unit = {
      taskRepo.store.store(key, state).futureValue
    }
  }

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(1, Seconds))

  test("should remove deployment version nodes, but keep deployment nodes") {
    Given("some deployment version nodes, a proper deployment node and an unrelated node")
    val f = new Fixture

    f.create("deployment:265fe17c-2979-4ab6-b906-9c2b34f9c429:2016-06-23T22:16:03.880Z", IndexedSeq.empty)
    f.create("deployment:42c6b840-5a4b-4110-a7d9-d4835f7499b9:2016-06-13T18:47:15.862Z", IndexedSeq.empty)
    f.create("deployment:fcabfa75-7756-4bc8-94b3-c9d5b2abd38c", IndexedSeq.empty)
    f.create("foo:bar", IndexedSeq.empty)

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

    val taskNoStatus = makeMarathonTaskState("/thereIsNoStatus", mesos.Protos.TaskState.TASK_STAGING) // we throw out STAGING on the next line anyway
    f.store("/thereIsNoStatus", taskNoStatus.copy(task = taskNoStatus.task.toBuilder.clearStatus().clearMarathonTaskStatus().build()))

    f.store("/running1", makeMarathonTaskState("/running1", mesos.Protos.TaskState.TASK_RUNNING))
    f.store("/running2", makeMarathonTaskState("/running2", mesos.Protos.TaskState.TASK_RUNNING))
    f.store("/running3", makeMarathonTaskState("/running3", mesos.Protos.TaskState.TASK_RUNNING, marathonTaskStatus = Some(MarathonTaskStatus.Running)))
    f.store("/unreachable1", makeMarathonTaskState("/unreachable1", mesos.Protos.TaskState.TASK_LOST, Some(TaskStatus.Reason.REASON_RECONCILIATION)))
    f.store("/gone1", makeMarathonTaskState("/gone1", mesos.Protos.TaskState.TASK_LOST, Some(TaskStatus.Reason.REASON_CONTAINER_LAUNCH_FAILED)))

    When("migrating")
    f.migration.migrate().futureValue

    Then("the tasks should all have a MarathonTaskStatus according their initial mesos task status")
    val storedTasks = (for {
      ids <- f.taskRepo.allIds()
      tasks <- {
        Future.sequence(ids.map(loadTask))
      }
    } yield tasks).futureValue

    storedTasks.size should be(6)
    storedTasks.foreach {
      task =>
        task.hasMarathonTaskStatus should be(true)
        task.getMarathonTaskStatus should not be (Protos.MarathonTask.MarathonTaskStatus.Invalid)

        val serializedTask = TaskSerializer.fromProto(task)
        val expectedStatus = serializedTask.mesosStatus.map(MarathonTaskStatus.apply(_)).getOrElse(MarathonTaskStatus.Unknown)
        val currentStatus = MarathonTaskStatusSerializer.fromProto(task.getMarathonTaskStatus)
        currentStatus should be equals expectedStatus
    }
  }

  test("Skip over bad tasks") {
    val f = new Fixture

    f.store.create("task:abc", IndexedSeq[Byte](1, 1, 1)).futureValue
    f.migration.migrate().futureValue
  }

  private def makeMarathonTaskState(taskId: String, taskState: mesos.Protos.TaskState, maybeReason: Option[TaskStatus.Reason] = None, marathonTaskStatus: Option[MarathonTaskStatus] = None): MarathonTaskState = {
    val mesosStatus = TaskStatusUpdateTestHelper.makeMesosTaskStatus(Task.Id.forRunSpec(taskId.toPath), taskState, maybeReason = maybeReason)
    val builder = MarathonTask.newBuilder()
      .setId(taskId)
      .setStatus(mesosStatus)
      .setHost("abc")
      .setStagedAt(1)
    if (marathonTaskStatus.isDefined) {
      builder.setMarathonTaskStatus(MarathonTaskStatusSerializer.toProto(marathonTaskStatus.get))
    }
    MarathonTaskState(builder.build())
  }

}
