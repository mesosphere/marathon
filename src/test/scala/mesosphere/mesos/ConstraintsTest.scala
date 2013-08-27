package mesosphere.mesos

import org.junit.Test
import mesosphere.marathon.Protos.{Constraint, MarathonTask}
import org.apache.mesos.Protos.Attribute
import org.apache.mesos.Protos.Value.Text
import org.junit.Assert._

/**
 * @author Florian Leibert (flo@leibert.de)
 */
class ConstraintsTest {

  def makeSampleTask(id: String, attr: String, attrVal: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .setPort(999)
      .setId(id)
      .addAttributes(
      Attribute.newBuilder()
        .setName(attr)
        .setText(Text.newBuilder()
        .setValue(attrVal))
        .setType(org.apache.mesos.Protos.Value.Type.TEXT)
        .build())
      .build()
  }

  def makeAttribute(attr: String, attrVal: String) = {
    Attribute.newBuilder()
      .setName(attr)
      .setText(Text.newBuilder()
      .setValue(attrVal))
      .setType(org.apache.mesos.Protos.Value.Type.TEXT)
      .build()
  }


  @Test
  def testConstraints() {
    val task1_rack1 = makeSampleTask("task1", "rackid", "1")
    val task2_rack1 = makeSampleTask("task2", "rackid", "1")
    val task3_rack2 = makeSampleTask("task3", "rackid", "2")

    val freshRack = Set()
    val sameRack = Set(task1_rack1, task2_rack1)
    val uniqueRack = Set(task1_rack1, task3_rack2)

    val clusterFreshRackMet = Constraints.meetsConstraint(freshRack.toSet,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "1")),
      "rackid",
      Constraint.Operator.CLUSTER_VALUE,
      None)

    assertTrue("Should be able to schedule in fresh rack.", clusterFreshRackMet)

    val clusterRackMet = Constraints.meetsConstraint(sameRack,
        Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "1")),
        "rackid",
        Constraint.Operator.CLUSTER_VALUE,
        None)
    assertTrue("Should meet clustered-in-rack constraints.",
      clusterRackMet)

    val clusterRackNotMet = Constraints.meetsConstraint(sameRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "2")),
      "rackid",
      Constraint.Operator.CLUSTER_VALUE,
      None)

    assertFalse("Should not meet cluster constraint.", clusterRackNotMet)

    val uniqueFreshRackMet = Constraints.meetsConstraint(freshRack.toSet,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid","1")),
      "rackid",
      Constraint.Operator.UNIQUE_VALUE,
      None)

    assertTrue(f"Should meet unique constraint for fresh rack." +
      f"${uniqueFreshRackMet}",
      uniqueFreshRackMet)

    val uniqueRackMet = Constraints.meetsConstraint(uniqueRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid","3")),
      "rackid",
      Constraint.Operator.UNIQUE_VALUE,
      None)

    assertTrue(f"Should meet unique constraint for rack: ${uniqueRack}.",
      uniqueRackMet)

    val uniqueRackNotMet = Constraints.meetsConstraint(sameRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid","1")),
      "rackid",
      Constraint.Operator.UNIQUE_VALUE,
      None)

    assertFalse(f"Should not meet unique constraint for rack. ${sameRack}",
      uniqueRackNotMet)

  }

}
