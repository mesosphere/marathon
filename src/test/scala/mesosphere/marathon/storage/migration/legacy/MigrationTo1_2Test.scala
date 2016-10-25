package mesosphere.marathon
package storage.migration.legacy

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.tracker.impl.{ TaskConditionSerializer, TaskSerializer }
import mesosphere.marathon.core.task.{ Task, TaskCondition }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.MarathonTaskState
import mesosphere.marathon.storage.LegacyInMemConfig
import mesosphere.marathon.storage.repository.TaskRepository
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonSpec }
import org.apache.mesos
import org.apache.mesos.Protos.TaskStatus
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.ExecutionContext

class MigrationTo1_2Test extends MarathonSpec with GivenWhenThen with Matchers with MarathonActorSupport {
  import mesosphere.marathon.state.PathId._

  implicit val ctx = ExecutionContext.global

  class Fixture {
    implicit lazy val metrics = new Metrics(new MetricRegistry)
    lazy val config = LegacyInMemConfig(25)
    lazy val store = config.store
    lazy val taskRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])

    lazy val migration = new MigrationTo1_2(Some(config))

    def create(key: String, bytes: IndexedSeq[Byte]): Unit = {
      store.create(key, bytes).futureValue
    }
    def store(key: String, state: MarathonTaskState): Unit = {
      taskRepo.store.store(key, state).futureValue
    }

  }

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

  test("should migrate tasks and add calculated Condition to stored tasks") {
    Given("some tasks without Condition")
    val f = new Fixture

    val taskNoStatus = makeMarathonTaskState("/thereIsNoStatus", mesos.Protos.TaskState.TASK_UNKNOWN)
    f.store("/thereIsNoStatus", taskNoStatus.copy(task = taskNoStatus.task.toBuilder.clearStatus().clearCondition().build()))

    f.store("/running1", makeMarathonTaskState("/running1", mesos.Protos.TaskState.TASK_RUNNING))
    f.store("/running2", makeMarathonTaskState("/running2", mesos.Protos.TaskState.TASK_RUNNING))
    f.store("/running3", makeMarathonTaskState("/running3", mesos.Protos.TaskState.TASK_RUNNING, maybeCondition = Some(Condition.Running)))
    f.store("/unreachable1", makeMarathonTaskState("/unreachable1", mesos.Protos.TaskState.TASK_LOST, Some(TaskStatus.Reason.REASON_RECONCILIATION)))
    f.store("/gone1", makeMarathonTaskState("/gone1", mesos.Protos.TaskState.TASK_LOST, Some(TaskStatus.Reason.REASON_CONTAINER_LAUNCH_FAILED)))

    When("migrating")
    f.migration.migrate().futureValue

    Then("the tasks should all have a Condition according their initial mesos task status")
    val storedTasks = f.taskRepo.all().map(TaskSerializer.toProto).runWith(Sink.seq).futureValue

    storedTasks.size should be(6)
    storedTasks.foreach {
      task =>
        task.hasCondition should be(true)
        task.getCondition should not be (Protos.MarathonTask.Condition.Invalid)

        val serializedTask = TaskSerializer.fromProto(task)
        val expectedStatus = serializedTask.status.mesosStatus.map(TaskCondition.apply(_)).getOrElse(Condition.Unknown)
        val currentCondition = TaskConditionSerializer.fromProto(task.getCondition)
        currentCondition should be equals expectedStatus
    }
  }

  private def makeMarathonTaskState(taskId: String, taskState: mesos.Protos.TaskState, maybeReason: Option[TaskStatus.Reason] = None, maybeCondition: Option[Condition] = None): MarathonTaskState = {
    val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(state = taskState, taskId = Task.Id.forRunSpec(taskId.toPath), maybeReason = maybeReason)
    val builder = MarathonTask.newBuilder()
      .setId(taskId)
      .setStatus(mesosStatus)
      .setHost("abc")
      .setStagedAt(1)
    maybeCondition.foreach(cond => builder.setCondition(TaskConditionSerializer.toProto(cond)))
    MarathonTaskState(builder.build())
  }

}
