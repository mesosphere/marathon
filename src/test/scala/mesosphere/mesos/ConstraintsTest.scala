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

    val clusterNoAttributeNotMet = Constraints.meetsConstraint(
      freshRack,
      makeOffer("foohost", Set()),
      clusterByRackId)

    assert(!clusterNoAttributeNotMet, "Should not meet cluster constraint.")

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

    val uniqueNoAttributeNotMet = Constraints.meetsConstraint(
      freshRack,
      makeOffer("foohost", Set()),
      uniqueRackId)

    assert(!uniqueNoAttributeNotMet, "Should not meet unique constraint.")
  }

  test("AttributesLikeByConstraints") {
    val task1_rack1 = makeSampleTask("task1", Map("foo" -> "bar"))
    val task2_rack1 = makeSampleTask("task2", Map("jdk" -> "7"))
    val freshRack = Set(task1_rack1, task2_rack1)
    val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.LIKE, "7")

    val likeVersionNotMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "6"))), // slave attributes
      jdk7Constraint)
    assert(!likeVersionNotMet, "Should not meet like-version constraints.")

    val likeVersionMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "7"))), // slave attributes
      jdk7Constraint)
    assert(likeVersionMet, "Should meet like-version constraints.")

    val likeNoAttributeNotMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set()), // no slave attribute
      jdk7Constraint)
    assert(!likeNoAttributeNotMet, "Should not meet like-no-attribute constraints.")
  }

  test("AttributesUnlikeByConstraints") {
    val task1_rack1 = makeSampleTask("task1", Map("foo" -> "bar"))
    val task2_rack1 = makeSampleTask("task2", Map("jdk" -> "7"))
    val freshRack = Set(task1_rack1, task2_rack1)
    val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.UNLIKE, "7")

    val unlikeVersionMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "6"))), // slave attributes
      jdk7Constraint)
    assert(unlikeVersionMet, "Should meet unlike-version constraints.")

    val unlikeVersionNotMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set(TextAttribute("jdk", "7"))), // slave attributes
      jdk7Constraint)
    assert(!unlikeVersionNotMet, "Should not meet unlike-version constraints.")

    val unlikeNoAttributeMet = Constraints.meetsConstraint(
      freshRack, // list of tasks register in the cluster
      makeOffer("foohost", Set()), // no slave attribute
      jdk7Constraint)
    assert(unlikeNoAttributeMet, "Should meet unlike-no-attribute constraints.")
  }

  test("RackGroupedByConstraints") {
    val task1_rack1 = makeSampleTask("task1", Map("rackid" -> "rack-1"))
    val task2_rack1 = makeSampleTask("task2", Map("rackid" -> "rack-1"))
    val task3_rack2 = makeSampleTask("task3", Map("rackid" -> "rack-2"))
    val task4_rack1 = makeSampleTask("task4", Map("rackid" -> "rack-1"))
    val task5_rack3 = makeSampleTask("task5", Map("rackid" -> "rack-3"))

    var sameRack = Set[MarathonTask]()

    val group2ByRack = makeConstraint("rackid", Constraint.Operator.GROUP_BY, "2")

    val groupByFreshRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(groupByFreshRackMet, "Should be able to schedule in fresh rack.")

    sameRack ++= Set(task1_rack1)

    val groupByRackMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(!groupByRackMet, "Should not meet group-by-rack constraints.")

    val groupByRackMet2 = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
      group2ByRack)

    assert(groupByRackMet2, "Should meet group-by-rack constraint.")

    sameRack ++= Set(task3_rack2)

    val groupByRackMet3 = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(groupByRackMet3, "Should meet group-by-rack constraints.")

    sameRack ++= Set(task2_rack1)

    val groupByRackNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
      group2ByRack)

    assert(!groupByRackNotMet, "Should not meet group-by-rack constraint.")

    val groupByNoAttributeNotMet = Constraints.meetsConstraint(
      sameRack,
      makeOffer("foohost", Set()),
      group2ByRack)
    assert(!groupByNoAttributeNotMet, "Should not meet group-by-no-attribute constraints.")
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

  test("HostnameGroupedByConstraints") {
    val task1_host1 = makeTaskWithHost("task1", "host1")
    val task2_host1 = makeTaskWithHost("task2", "host1")
    val task3_host2 = makeTaskWithHost("task3", "host2")
    val task4_host3 = makeTaskWithHost("task4", "host3")

    var groupHost = Set[MarathonTask]()
    val attributes: Set[Attribute] = Set()

    val groupByHost = makeConstraint("hostname", Constraint.Operator.GROUP_BY, "2")

    val groupByFreshHostMet = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host1", attributes),
      groupByHost)

    assert(groupByFreshHostMet, "Should be able to schedule in fresh host.")

    groupHost ++= Set(task1_host1)

    val groupByHostMet = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host1", attributes),
      groupByHost)

    assert(!groupByHostMet, "Should not meet group-by-host constraint.")

    val groupByHostMet2 = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host2", attributes),
      groupByHost)

    assert(groupByHostMet2, "Should meet group-by-host constraint.")

    groupHost ++= Set(task3_host2)

    val groupByHostMet3 = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host1", attributes),
      groupByHost)

    assert(groupByHostMet3, "Should meet group-by-host constraint.")

    groupHost ++= Set(task2_host1)

    val groupByHostNotMet = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host1", attributes),
      groupByHost)

    assert(!groupByHostNotMet, "Should not meet group-by-host constraint.")

    val groupByHostMet4 = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host3", attributes),
      groupByHost)

    assert(groupByHostMet4, "Should meet group-by-host constraint.")

    groupHost ++= Set(task4_host3)

    val groupByHostNotMet2 = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host1", attributes),
      groupByHost)

    assert(!groupByHostNotMet2, "Should not meet group-by-host constraint.")

    val groupByHostMet5 = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host3", attributes),
      groupByHost)

    assert(groupByHostMet5, "Should meet group-by-host constraint.")

    val groupByHostMet6 = Constraints.meetsConstraint(
      groupHost,
      makeOffer("host2", attributes),
      groupByHost)

    assert(groupByHostMet6, "Should meet group-by-host constraint.")
  }
}
