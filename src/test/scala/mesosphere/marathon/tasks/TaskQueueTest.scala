package mesosphere.marathon.tasks

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.TaskQueue.QueuedTask

import scala.collection.immutable.Seq
import scala.concurrent.duration.Deadline

class TaskQueueTest extends MarathonSpec {
  val app1 = AppDefinition(id = "app1".toPath, constraints = Set.empty)
  val app2 = AppDefinition(id = "app2".toPath, constraints = Set(buildConstraint("hostname", "UNIQUE"), buildConstraint("rack_id", "CLUSTER", "rack-1")))
  val app3 = AppDefinition(id = "app3".toPath, constraints = Set(buildConstraint("hostname", "UNIQUE")))

  var queue: TaskQueue = null

  before {
    val metricRegistry = new MetricRegistry
    queue = new TaskQueue()
  }

  def buildConstraint(field: String, operator: String, value: String = ""): Constraint = {
    Constraint.newBuilder()
      .setField(field)
      .setOperator(Constraint.Operator.valueOf(operator))
      .setValue(value)
      .build()
  }

  test("Priority") {
    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assert(app2 == queue.poll().get.app, s"Should return $app2")
    assert(app3 == queue.poll().get.app, s"Should return $app3")
    assert(app1 == queue.poll().get.app, s"Should return $app1")
  }

  test("Retain") {
    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assert(queue.list.size == 3, "Queue should contain 3 elements.")
    queue.retain { case QueuedTask(app, _) => app.id == app2.id }
    assert(queue.list.size == 1, "Queue should contain 1 elements.")
  }

  test("RemoveAll") {
    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    val res = queue.removeAll().map(_.app)

    assert(Vector(app2, app3, app1) == res, s"Should return all elements in correct order.")
    assert(queue.queue.isEmpty, "TaskQueue should be empty.")
  }

  test("AddAll") {
    val queue = new TaskQueue

    queue.addAll(Seq(
      QueuedTask(app1, Deadline.now),
      QueuedTask(app2, Deadline.now),
      QueuedTask(app3, Deadline.now)
    ))

    assert(queue.list.size == 3, "Queue should contain 3 elements.")
    assert(queue.count(app1) == 1, s"Queue should contain $app1.")
    assert(queue.count(app2) == 1, s"Queue should contain $app2.")
    assert(queue.count(app3) == 1, s"Queue should contain $app3.")
  }
}
