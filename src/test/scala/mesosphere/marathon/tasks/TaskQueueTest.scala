package mesosphere.marathon.tasks

import mesosphere.marathon.{ MarathonTestHelper, MarathonSchedulerDriverHolder, MarathonSpec }
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.TaskQueue.QueuedTask

class TaskQueueTest extends MarathonSpec {
  val app1 = AppDefinition(id = "app1".toPath, constraints = Set.empty)
  val app2 = AppDefinition(id = "app2".toPath, constraints = Set(buildConstraint("hostname", "UNIQUE"), buildConstraint("rack_id", "CLUSTER", "rack-1")))
  val app3 = AppDefinition(id = "app3".toPath, constraints = Set(buildConstraint("hostname", "UNIQUE")))

  var queue: TaskQueue = _

  before {
    queue = new TaskQueue(MarathonTestHelper.defaultConfig(), OfferReviverDummy())
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

  test("poll") {
    queue.add(app1, 3)

    assert(queue.count(app1.id) == 3)
    assert(queue.poll().map(_.app) == Some(app1))
    assert(queue.count(app1.id) == 2)
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

  test("Don't try to match apps with a count of 0") {
    queue.add(app1, 1)
    queue.poll()
    assert(queue.count(app1.id) == 0)
    var counter = 0

    val matching = queue.pollMatching {
      case x if x.id == app1.id =>
        counter += 1
        Some(x)

      case _ => None
    }

    assert(matching.isEmpty)
    assert(counter == 0)
  }

  // regression test for #1155
  test("Don't list tasks with a count of 0") {
    queue.add(app1)
    queue.add(app2)
    queue.poll()

    assert(queue.list.forall(_.count.get > 0))
  }

  test("List tasks with delay") {
    queue.add(app1, 1)
    queue.rateLimiter.addDelay(app1)
    val withDelay = queue.listWithDelay

    println(withDelay)

    assert(withDelay.size == 1)
    assert(withDelay.headOption.exists {
      case (task, delay) =>
        task.count.get() == 1 &&
          task.app == app1 &&
          delay == queue.rateLimiter.getDelay(app1)
    })
  }
}
