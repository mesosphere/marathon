package mesosphere.marathon.tasks

import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import org.junit.Test
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Protos.Constraint
import org.junit.Assert._

class TaskQueueTest extends AssertionsForJUnit with MockitoSugar {
  val app1 = AppDefinition(id = "app1", constraints = Set.empty)
  val app2 = AppDefinition(id = "app2", constraints = Set(buildConstraint("hostname", "UNIQUE"), buildConstraint("rack_id", "CLUSTER", "rack-1")))
  val app3 = AppDefinition(id = "app3", constraints = Set(buildConstraint("hostname", "UNIQUE")))

  def buildConstraint(field: String, operator: String, value: String = ""): Constraint = {
    val constraint = Constraint.newBuilder()

    constraint.setField(field)
    constraint.setOperator(Constraint.Operator.valueOf(operator))
    constraint.setValue(value)

    constraint.build()
  }

  @Test
  def testPriority() {
    val queue = new TaskQueue

    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    assertEquals(s"Should return $app2", app2, queue.poll())
    assertEquals(s"Should return $app3", app3, queue.poll())
    assertEquals(s"Should return $app1", app1, queue.poll())
  }

  @Test
  def testRemoveAll() {
    val queue = new TaskQueue

    queue.add(app1)
    queue.add(app2)
    queue.add(app3)

    val res = queue.removeAll()

    assertEquals(s"Should return all elements in correct order.", Vector(app2, app3, app1), res)
    assertTrue("TaskQueue should be empty.", queue.queue.isEmpty)
  }

  @Test
  def testAddAll() {
    val queue = new TaskQueue

    queue.addAll(Seq(app1, app2, app3))

    assertTrue("Queue should contain 3 elements.", queue.queue.size() == 3)
    assertTrue(s"Queue should contains $app1.", queue.queue.contains(app1))
    assertTrue(s"Queue should contains $app2.", queue.queue.contains(app2))
    assertTrue(s"Queue should contains $app3.", queue.queue.contains(app3))

    val res = queue.removeAll()

    assertEquals(s"Should return all elements in correct order.", Vector(app2, app3, app1), res)
  }

}
