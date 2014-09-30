package mesosphere.mesos

import mesosphere.marathon.Protos.{ Constraint, MarathonTask }
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.Constraint.Operator
import scala.collection.JavaConverters._
import scala.util.Random
import mesosphere.mesos.protos.{ FrameworkID, SlaveID, OfferID, TextAttribute }
import org.apache.mesos.Protos.{ Offer, Attribute }
import mesosphere.marathon.MarathonSpec

class ConstraintsTest extends MarathonSpec {

  import mesosphere.mesos.protos.Implicits._

  def makeSampleTask(id: String, attrs: Map[String, String]) = {
    val builder = MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)

    for ((name, value) <- attrs) {
      builder.addAttributes(TextAttribute(name, value))
    }
    builder.build()
  }

  def makeOffer(hostname: String, attributes: Iterable[Attribute]) = {
    Offer.newBuilder
      .setId(OfferID(Random.nextString(9)))
      .setSlaveId(SlaveID(Random.nextString(9)))
      .setFrameworkId(FrameworkID(Random.nextString(9)))
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

  test("UniqueHostConstraint") {
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

    assert(firstTaskOnHost, "Should meet first task constraint.")

    val wrongHostName = Constraints.meetsConstraint(
      firstTask,
      makeOffer("wrong.com", attributes),
      makeConstraint("hostname", Operator.CLUSTER, "right.com"))

    assert(!wrongHostName, "Should not accept the wrong hostname.")

    val differentHosts = Set(task1_host1, task2_host2, task3_host3)

    val differentHostsDifferentTasks = Constraints.meetsConstraint(
      differentHosts,
      makeOffer("host4", attributes),
      hostnameUnique)

    assert(differentHostsDifferentTasks, "Should place host in array")

    val reusingOneHost = Constraints.meetsConstraint(
      differentHosts,
      makeOffer("host2", attributes),
      hostnameUnique)

    assert(!reusingOneHost, "Should not place host")

    val firstOfferFirstTaskInstance = Constraints.meetsConstraint(
      firstTask,
      makeOffer("host2", attributes),
      hostnameUnique)

    assert(firstOfferFirstTaskInstance, "Should not place host")
  }

  test("RackConstraints") {
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
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      clusterByRackId)

    assert(clusterFreshRackMet, "Should be able to schedule in fresh rack.")

    val clusterRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      clusterByRackId)
    assert(clusterRackMet, "Should meet clustered-in-rack constraints.")

    val clusterRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
      clusterByRackId)

    assert(!clusterRackNotMet, "Should not meet cluster constraint.")

    val uniqueFreshRackMet = Constraints.meetsConstraint(
      freshRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      uniqueRackId)

    assert(uniqueFreshRackMet, "Should meet unique constraint for fresh rack.")

    val uniqueRackMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-3"))),
      uniqueRackId)

    assert(uniqueRackMet, "Should meet unique constraint for rack")

    val uniqueRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      uniqueRackId)

    assert(!uniqueRackNotMet, "Should not meet unique constraint for rack.")
  }

  test("AttributesLikeByConstraints") {
    val task1_rack1 = makeSampleTask("task1", Map("foo" -> "bar"))
    val task2_rack1 = makeSampleTask("task2", Map("jdk" -> "7"))
    val freshRack = Set(task1_rack1, task2_rack1)
    val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.LIKE, "7")

    val clusterNotMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "6"))), // slave attributes
      jdk7Constraint)
    assert(!clusterNotMet, "Should not meet cluster constraints.")

    val clusterMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "7"))), // slave attributes
      jdk7Constraint)
    assert(clusterMet, "Should meet cluster constraints.")
  }

  test("AttributesUnlikeByConstraints") {
    val task1_rack1 = makeSampleTask("task1", Map("foo" -> "bar"))
    val task2_rack1 = makeSampleTask("task2", Map("jdk" -> "7"))
    val freshRack = Set(task1_rack1, task2_rack1)
    val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.UNLIKE, "7")

    val clusterMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "6"))), // slave attributes
      jdk7Constraint)
    assert(clusterMet, "Should meet cluster constraints.")

    val clusterNotMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "7"))), // slave attributes
      jdk7Constraint)
    assert(!clusterNotMet, "Should not meet cluster constraints.")
  }

  test("RackGroupedByConstraints") {
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
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(clusterFreshRackMet, "Should be able to schedule in fresh rack.")

    sameRack ++= Set(task1_rack1)

    val clusterRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(!clusterRackMet, "Should not meet clustered-in-rack constraints.")

    val clusterRackMet2 = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
      group2ByRack)

    assert(clusterRackMet2, "Should meet cluster constraint.")

    sameRack ++= Set(task3_rack2)

    val clusterRackMet3 = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(clusterRackMet3, "Should meet clustered-in-rack constraints.")

    sameRack ++= Set(task2_rack1)

    val clusterRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(!clusterRackNotMet, "Should not meet cluster constraint.")

    val uniqueFreshRackMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      rackIdUnique)

    assert(uniqueFreshRackMet, "Should meet unique constraint for fresh rack.")

    uniqueRack ++= Set(task4_rack1)

    val uniqueRackMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-3"))),
      rackIdUnique)

    assert(uniqueRackMet, "Should meet unique constraint for rack.")

    uniqueRack ++= Set(task5_rack3)

    val uniqueRackNotMet = Constraints.meetsConstraint(
      uniqueRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      rackIdUnique)

    assert(!uniqueRackNotMet, "Should not meet unique constraint for rack.")
  }

  test("RackGroupedByConstraints2") {
    val task1_rack1 = makeSampleTask("task1", Map("rackid" -> "rack-1"))
    val task2_rack2 = makeSampleTask("task2", Map("rackid" -> "rack-2"))
    val task3_rack3 = makeSampleTask("task3", Map("rackid" -> "rack-3"))
    val task4_rack1 = makeSampleTask("task4", Map("rackid" -> "rack-1"))
    val task5_rack2 = makeSampleTask("task5", Map("rackid" -> "rack-2"))

    var groupRack = Set[MarathonTask]()

    val groupByRack = makeConstraint("rackid", Constraint.Operator.GROUP_BY, "3")

    val clusterFreshRackMet = Constraints.meetsConstraint(
      groupRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      groupByRack)

    assert(clusterFreshRackMet, "Should be able to schedule in fresh rack.")

    groupRack ++= Set(task1_rack1)

    val clusterRackMet1 = Constraints.meetsConstraint(
      groupRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
      groupByRack)

    assert(clusterRackMet1, "Should meet clustered-in-rack constraints.")

    groupRack ++= Set(task2_rack2)

    val clusterRackMet2 = Constraints.meetsConstraint(
      groupRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-3"))),
      groupByRack)

    assert(clusterRackMet2, "Should meet clustered-in-rack constraints.")

    groupRack ++= Set(task3_rack3)

    val clusterRackMet3 = Constraints.meetsConstraint(
      groupRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      groupByRack)

    assert(clusterRackMet3, "Should meet clustered-in-rack constraints.")

    groupRack ++= Set(task4_rack1)

    val clusterRackMet4 = Constraints.meetsConstraint(
      groupRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
      groupByRack)

    assert(clusterRackMet4, "Should meet clustered-in-rack constraints.")
  }
}
