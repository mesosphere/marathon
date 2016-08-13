package mesosphere.marathon.storage.migration.legacy.legacy

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.tracker.impl.{ MarathonTaskStatusSerializer, TaskSerializer }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.MarathonTaskState
import mesosphere.marathon.storage.LegacyInMemConfig
import mesosphere.marathon.storage.repository.TaskRepository
import mesosphere.marathon.test.MarathonActorSupport
import org.apache.mesos
import org.apache.mesos.Protos.TaskStatus
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.ExecutionContext

class MigrationTo1_2Test extends MarathonSpec with GivenWhenThen with Matchers with MarathonActorSupport {
  import mesosphere.FutureTestSupport._
  import mesosphere.marathon.state.PathId._

  implicit val ctx = ExecutionContext.global

  class Fixture {
    implicit lazy val metrics = new Metrics(new MetricRegistry)
    lazy val config = LegacyInMemConfig(25)
    lazy val store = config.store
    lazy val taskRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])

    lazy val migration = new MigrationTo1_2(Some(config))
  }

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(1, Seconds))

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

    val store = f.taskRepo.store

    store.store("/running1", makeMarathonTaskState("/running1", mesos.Protos.TaskState.TASK_RUNNING))
    store.store("/running2", makeMarathonTaskState("/running2", mesos.Protos.TaskState.TASK_RUNNING))
    store.store("/running3", makeMarathonTaskState("/running3", mesos.Protos.TaskState.TASK_RUNNING, marathonTaskStatus = Some(MarathonTaskStatus.Running)))
    store.store("/unreachable1", makeMarathonTaskState("/unreachable1", mesos.Protos.TaskState.TASK_LOST, Some(TaskStatus.Reason.REASON_RECONCILIATION)))
    store.store("/gone1", makeMarathonTaskState("/gone1", mesos.Protos.TaskState.TASK_LOST, Some(TaskStatus.Reason.REASON_CONTAINER_LAUNCH_FAILED)))

    When("migrating")
    f.migration.migrate().futureValue

    Then("the tasks should all have a MarathonTaskStatus according their initial mesos task status")
    val storedTasks = f.taskRepo.all().map(TaskSerializer.toProto).runWith(Sink.seq)

    storedTasks.futureValue.foreach {
      task =>
        task.getMarathonTaskStatus should not be null
        val serializedTask = TaskSerializer.fromProto(task)
        val expectedStatus = MarathonTaskStatus(serializedTask.mesosStatus.getOrElse(fail("Task has no mesos task status")))
        val currentStatus = MarathonTaskStatusSerializer.fromProto(task.getMarathonTaskStatus)

        currentStatus should be equals expectedStatus
    }
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
