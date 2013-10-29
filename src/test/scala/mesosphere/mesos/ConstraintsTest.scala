package mesosphere.mesos

import org.junit.Test
import mesosphere.marathon.Protos.{Constraint, MarathonTask}
import org.apache.mesos.Protos.Attribute
import org.apache.mesos.Protos.Value.Text
import org.junit.Assert._
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.Constraint.Operator

/**
 * @author Florian Leibert (flo@leibert.de)
 */
class ConstraintsTest {

  def makeSampleTask(id: String, attr: String, attrVal: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
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

  def makeTaskWithHost(id: String, host: String) = {
    MarathonTask.newBuilder()
      .setHost(host)
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .build()
  }

  @Test
  def testUniqueHostConstraint() {
    val task1_host1 = makeTaskWithHost("task1", "host1")
    val task2_host2 = makeTaskWithHost("task2", "host2")
    val task3_host3 = makeTaskWithHost("task3", "host3")
    val attributes: Set[Attribute] = Set()

    val firstTask = Set()

    val firstTaskOnHost = Constraints.meetsConstraint(firstTask.toSet,
      attributes,
      "foohost",
      "hostname",
      Operator.CLUSTER,
      None)

    assertTrue("Should meet first task constraint.", firstTaskOnHost)

    val differentHosts = Set(task1_host1, task2_host2, task3_host3)

    val differentHostsDifferentTasks = Constraints.meetsConstraint(
      differentHosts.toSet,
      attributes,
      "host4",
      "hostname",
      Operator.UNIQUE,
      None)

    assertTrue("Should place host in array", differentHostsDifferentTasks)

    val reusingOneHost = Constraints.meetsConstraint(
      differentHosts.toSet,
      attributes,
      "host2",
      "hostname",
      Operator.UNIQUE,
      None)

    assertFalse("Should not place host", reusingOneHost)

    val firstOfferFirstTaskInstance = Constraints.meetsConstraint(
      firstTask.toSet,
      attributes,
      "host2",
      "hostname",
      Operator.CLUSTER,
      None)

    assertTrue("Should not place host", firstOfferFirstTaskInstance)
  }

  @Test
  def testRackConstraints() {
    val task1_rack1 = makeSampleTask("task1", "rackid", "1")
    val task2_rack1 = makeSampleTask("task2", "rackid", "1")
    val task3_rack2 = makeSampleTask("task3", "rackid", "2")

    val freshRack = Set()
    val sameRack = Set(task1_rack1, task2_rack1)
    val uniqueRack = Set(task1_rack1, task3_rack2)

    val clusterFreshRackMet = Constraints.meetsConstraint(freshRack.toSet,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "1")),
      "foohost",
      "rackid",
      Constraint.Operator.CLUSTER,
      None)

    assertTrue("Should be able to schedule in fresh rack.", clusterFreshRackMet)

    val clusterRackMet = Constraints.meetsConstraint(sameRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "1")),
      "foohost",
      "rackid",
      Constraint.Operator.CLUSTER,
      None)
    assertTrue("Should meet clustered-in-rack constraints.",
      clusterRackMet)

    val clusterRackNotMet = Constraints.meetsConstraint(sameRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "2")),
      "foohost",
      "rackid",
      Constraint.Operator.CLUSTER,
      None)

    assertFalse("Should not meet cluster constraint.", clusterRackNotMet)

    val uniqueFreshRackMet = Constraints.meetsConstraint(freshRack.toSet,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "1")),
      "foohost",
      "rackid",
      Constraint.Operator.UNIQUE,
      None)

    assertTrue(f"Should meet unique constraint for fresh rack." +
      f"${uniqueFreshRackMet}",
      uniqueFreshRackMet)

    val uniqueRackMet = Constraints.meetsConstraint(uniqueRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "3")),
      "foohost",
      "rackid",
      Constraint.Operator.UNIQUE,
      None)

    assertTrue(f"Should meet unique constraint for rack: ${uniqueRack}.",
      uniqueRackMet)

    val uniqueRackNotMet = Constraints.meetsConstraint(sameRack,
      Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "1")),
      "foohost",
      "rackid",
      Constraint.Operator.UNIQUE,
      None)

    assertFalse(f"Should not meet unique constraint for rack. ${sameRack}",
      uniqueRackNotMet)

  }

}
