package mesosphere.marathon.tasks

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.MarathonSpec

class TaskQueueTest extends MarathonSpec {
  val app1 = AppDefinition(id = "app1", constraints = Set.empty)
  val app2 = AppDefinition(id = "app2", constraints = Set(buildConstraint("hostname", "UNIQUE"), buildConstraint("rack_id", "CLUSTER", "rack-1")))
  val app3 = AppDefinition(id = "app3", constraints = Set(buildConstraint("hostname", "UNIQUE")))

  def buildConstraint(field: String, operator: String, value: String = ""): Constraint = {
    Constraint.newBuilder()
      .setField(field)
      .setOperator(Constraint.Operator.valueOf(operator))
      .setValue(value)
      .build()
  }

  test("Priority") {
    val queue = new TaskQueue

    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assert(app2 == queue.poll(), s"Should return $app2")
    assert(app3 == queue.poll(), s"Should return $app3")
    assert(app1 == queue.poll(), s"Should return $app1")
  }

  test("RemoveAll") {
    val queue = new TaskQueue

    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    val res = queue.removeAll()

    assert(Vector(app2, app3, app1) == res, s"Should return all elements in correct order.")
    assert(queue.queue.isEmpty, "TaskQueue should be empty.")
  }

  test("AddAll") {
    val queue = new TaskQueue

    queue.addAll(Seq(app1, app2, app3))

    assert(queue.queue.size() == 3, "Queue should contain 3 elements.")
    assert(queue.queue.contains(app1), s"Queue should contain $app1.")
    assert(queue.queue.contains(app2), s"Queue should contain $app2.")
    assert(queue.queue.contains(app3), s"Queue should contain $app3.")

    val res = queue.removeAll()

    assert(Vector(app2, app3, app1) == res, s"Should return all elements in correct order.")
  }
}
