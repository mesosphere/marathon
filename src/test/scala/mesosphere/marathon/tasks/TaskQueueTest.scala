package mesosphere.marathon.tasks

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.TaskQueue.QueuedTask

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
    queue.retain { case QueuedTask(app, _, _) => app.id == app2.id }
    assert(queue.list.size == 1, "Queue should contain 1 elements.")
  }

  test("pollMatching") {
    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assert(Some(app1) == queue.pollMatching {
      case x if x.id == "app1".toPath => Some(x)
      case _                          => None
    })
  }

  test("pollMatching Priority") {
    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assert(Some(app2) == queue.pollMatching(Some(_)))
  }

  test("pollMatching no match") {
    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assert(None == queue.pollMatching {
      case x if x.id == "DOES_NOT_EXIST".toPath => Some(x)
      case _                                    => None
    })
  }
}
