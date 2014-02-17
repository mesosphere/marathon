package mesosphere.mesos

import org.junit.Test
import mesosphere.marathon.Protos.{Constraint, MarathonTask}
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.Text
import org.junit.Assert._
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.Constraint.Operator
import scala.collection.JavaConverters._
import scala.util.Random

/**
 * @author Florian Leibert (flo@leibert.de)
 */
class ConstraintsTest {

  def makeSampleTask(id: String, attrs: Map[String, String]) = {
    val attrProtos = for {
      (name, value) <- attrs
    } yield {
      makeAttribute(name, value)
    }

    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .addAllAttributes(attrProtos.asJava)
      .build()
  }

  def makeAttribute(attr: String, attrVal: String) = {
    Attribute.newBuilder()
      .setName(attr)
      .setText(Text.newBuilder().setValue(attrVal))
      .setType(org.apache.mesos.Protos.Value.Type.TEXT)
      .build()
  }

  def makeOffer(hostname: String, attributes: Iterable[Attribute]) = {
    Offer.newBuilder
      .setId(OfferID.newBuilder.setValue(Random.nextString(9)))
      .setSlaveId(SlaveID.newBuilder.setValue(Random.nextString(9)))
      .setFrameworkId(FrameworkID.newBuilder.setValue(Random.nextString(9)))
      .setHostname(hostname)
      .addAllAttributes(attributes.asJava)
      .build
  }

  def makeTaskWithHost(id: String, host: String) = {
    MarathonTask.newBuilder()
      .setHost(host)
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .build()
  }

  def makeConstraint(field: String, operator: Operator, value: String) = {
    Constraint.newBuilder
      .setField(field)
      .setOperator(operator)
      .setValue(value)
      .build
  }

  @Test
  def testUniqueHostConstraint() {
    val task1_host1 = makeTaskWithHost("task1", "host1")
    val task2_host2 = makeTaskWithHost("task2", "host2")
    val task3_host3 = makeTaskWithHost("task3", "host3")
    val attributes: Set[Attribute] = Set()

    val firstTask = Set()

    val hostnameUnique = makeConstraint("hostname", Operator.UNIQUE, "")

    val firstTaskOnHost = Constraints.meetsConstraint(
      firstTask,
      makeOffer("foohost", attributes),
      makeConstraint("hostname", Operator.CLUSTER, ""))

    assertTrue("Should meet first task constraint.", firstTaskOnHost)

    val wrongHostName = Constraints.meetsConstraint(
      firstTask,
      makeOffer("wrong.com", attributes),
      makeConstraint("hostname", Operator.CLUSTER, "right.com"))

    assertFalse("Should not accept the wrong hostname.", wrongHostName)

    val differentHosts = Set(task1_host1, task2_host2, task3_host3)

    val differentHostsDifferentTasks = Constraints.meetsConstraint(
      differentHosts,
      makeOffer("host4", attributes),
      hostnameUnique)

    assertTrue("Should place host in array", differentHostsDifferentTasks)

    val reusingOneHost = Constraints.meetsConstraint(
      differentHosts,
      makeOffer("host2", attributes),
      hostnameUnique)

    assertFalse("Should not place host", reusingOneHost)

    val firstOfferFirstTaskInstance = Constraints.meetsConstraint(
      firstTask,
      makeOffer("host2", attributes),
      hostnameUnique)

    assertTrue("Should not place host", firstOfferFirstTaskInstance)
  }

  @Test
  def testRackConstraints() {
    val task1_rack1 = makeSampleTask("task1", Map("rackid" -> "rack-1"))
    val task2_rack1 = makeSampleTask("task2", Map("rackid" -> "rack-1"))
    val task3_rack2 = makeSampleTask("task3", Map("rackid" -> "rack-2"))

    val freshRack = Set()
    val sameRack = Set(task1_rack1, task2_rack1)
    val uniqueRack = Set(task1_rack1, task3_rack2)

    val clusterByRackId = makeConstraint("rackid", Constraint.Operator.CLUSTER, "")
    val uniqueRackId = makeConstraint("rackid", Constraint.Operator.UNIQUE, "")

    val clusterFreshRackMet = Constraints.meetsConstraint(
      freshRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      clusterByRackId)

    assertTrue("Should be able to schedule in fresh rack.", clusterFreshRackMet)

    val clusterRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      clusterByRackId)
    assertTrue("Should meet clustered-in-rack constraints.",
      clusterRackMet)

    val clusterRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-2"))),
      clusterByRackId)

    assertFalse("Should not meet cluster constraint.", clusterRackNotMet)

    val uniqueFreshRackMet = Constraints.meetsConstraint(
      freshRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      uniqueRackId)

    assertTrue(f"Should meet unique constraint for fresh rack." +
      f"${uniqueFreshRackMet}",
      uniqueFreshRackMet)

    val uniqueRackMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-3"))),
      uniqueRackId)

    assertTrue(f"Should meet unique constraint for rack: ${uniqueRack}.",
      uniqueRackMet)

    val uniqueRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      uniqueRackId)

    assertFalse(f"Should not meet unique constraint for rack. ${sameRack}",
      uniqueRackNotMet)

  }

  @Test
  def testAttributesLikeByConstraints() {
    val task1_rack1 = makeSampleTask("task1", Map("foo" -> "bar"))
    val task2_rack1 = makeSampleTask("task2", Map("jdk" -> "7"))
    val freshRack = Set(task1_rack1, task2_rack1)
    val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.LIKE, "7")

    val clusterNotMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(makeAttribute("jdk", "6"))),  // slave attributes
      jdk7Constraint)
    assertFalse("Should not meet cluster constraints.", clusterNotMet)

    val clusterMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(makeAttribute("jdk", "7"))),  // slave attributes
      jdk7Constraint)
    assertTrue("Should meet cluster constraints.", clusterMet)
  }

  @Test
  def testRackGroupedByConstraints() {
    val task1_rack1 = makeSampleTask("task1", Map("rackid" -> "rack-1"))
    val task2_rack1 = makeSampleTask("task2", Map("rackid" -> "rack-1"))
    val task3_rack2 = makeSampleTask("task3", Map("rackid" -> "rack-2"))
    val task4_rack1 = makeSampleTask("task4", Map("rackid" -> "rack-1"))
    val task5_rack3 = makeSampleTask("task5", Map("rackid" -> "rack-3"))

    var sameRack = Set[MarathonTask]()
    var uniqueRack = Set[MarathonTask]()

    val group2ByRack = makeConstraint("rackid", Constraint.Operator.GROUP_BY, "2")
    val rackIdUnique = makeConstraint("rackid", Constraint.Operator.UNIQUE, "")

    val clusterFreshRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      group2ByRack)

    assertTrue("Should be able to schedule in fresh rack.", clusterFreshRackMet)

    sameRack ++= Set(task1_rack1)

    val clusterRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      group2ByRack)

    assertFalse("Should not meet clustered-in-rack constraints.",
      clusterRackMet)

    val clusterRackMet2 = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-2"))),
      group2ByRack)

    assertTrue("Should meet cluster constraint.", clusterRackMet2)

    sameRack ++= Set(task3_rack2)

    val clusterRackMet3 = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      group2ByRack)

    assertTrue("Should meet clustered-in-rack constraints.",
      clusterRackMet3)

    sameRack ++= Set(task2_rack1)

    val clusterRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      group2ByRack)

    assertFalse("Should not meet cluster constraint.", clusterRackNotMet)

    val uniqueFreshRackMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      rackIdUnique)

    assertTrue(f"Should meet unique constraint for fresh rack." +
      f"${uniqueFreshRackMet}",
      uniqueFreshRackMet)

    uniqueRack ++= Set(task4_rack1)

    val uniqueRackMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-3"))),
      rackIdUnique)

    assertTrue(f"Should meet unique constraint for rack: ${uniqueRack}.",
      uniqueRackMet)

    uniqueRack ++= Set(task5_rack3)

    val uniqueRackNotMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(makeAttribute("foo", "bar"), makeAttribute("rackid", "rack-1"))),
      rackIdUnique)

    assertFalse(f"Should not meet unique constraint for rack. ${sameRack}",
      uniqueRackNotMet)
  }
}
