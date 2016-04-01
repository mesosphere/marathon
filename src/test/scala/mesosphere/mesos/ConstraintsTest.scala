package mesosphere.mesos

import mesosphere.marathon.MarathonTestHelper.Implicits._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.{ FrameworkID, OfferID, SlaveID, TextAttribute }
import org.apache.mesos.Protos.{ Attribute, Offer }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.util.Random

class ConstraintsTest extends MarathonSpec with GivenWhenThen with Matchers {

  import mesosphere.mesos.protos.Implicits._

  test("Select tasks to kill for a single group by works") {
    Given("app with hostname group_by and 20 tasks even distributed on 2 hosts")
    val app = AppDefinition(constraints = Set(makeConstraint("hostname", Operator.GROUP_BY, "")))
    val tasks = 0.to(19).map(num => makeTaskWithHost(s"$num", s"srv${num % 2}"))

    When("10 tasks should be selected to kill")
    val result = Constraints.selectTasksToKill(app, tasks, 10)

    Then("10 tasks got selected and evenly distributed")
    result should have size 10
    val dist = result.groupBy(_.taskId.idString.toInt % 2 == 1)
    dist should have size 2
    dist.values.head should have size 5
  }

  test("Select only tasks to kill for an unbalanced distribution") {
    Given("app with hostname group_by and 30 tasks uneven distributed on 2 hosts")
    val app = AppDefinition(constraints = Set(makeConstraint("hostname", Operator.GROUP_BY, "")))
    val tasks = 0.to(19).map(num => makeTaskWithHost(s"$num", s"srv1")) ++
      20.to(29).map(num => makeTaskWithHost(s"$num", s"srv2"))

    When("10 tasks should be selected to kill")
    val result = Constraints.selectTasksToKill(app, tasks, 10)

    Then("All 10 tasks are from srv1")
    result should have size 10
    result.forall(_.agentInfo.host == "srv1") should be(true)
  }

  test("Select tasks to kill for multiple group by works") {
    Given("app with 2 group_by distributions and 40 tasks even distributed")
    val app = AppDefinition(constraints = Set(
      makeConstraint("rack", Operator.GROUP_BY, ""),
      makeConstraint("color", Operator.GROUP_BY, "")))
    val tasks =
      0.to(9).map(num => makeSampleTask(s"$num", Map("rack" -> "rack-1", "color" -> "blue"))) ++
        10.to(19).map(num => makeSampleTask(s"$num", Map("rack" -> "rack-1", "color" -> "green"))) ++
        20.to(29).map(num => makeSampleTask(s"$num", Map("rack" -> "rack-2", "color" -> "blue"))) ++
        30.to(39).map(num => makeSampleTask(s"$num", Map("rack" -> "rack-2", "color" -> "green")))

    When("20 tasks should be selected to kill")
    val result = Constraints.selectTasksToKill(app, tasks, 20)

    Then("20 tasks got selected and evenly distributed")
    result should have size 20
    result.count(_.agentInfo.attributes.exists(_.getText.getValue == "rack-1")) should be(10)
    result.count(_.agentInfo.attributes.exists(_.getText.getValue == "rack-2")) should be(10)
    result.count(_.agentInfo.attributes.exists(_.getText.getValue == "blue")) should be(10)
    result.count(_.agentInfo.attributes.exists(_.getText.getValue == "green")) should be(10)
  }

  test("Does not select any task without constraint") {
    Given("app with hostname group_by and 10 tasks even distributed on 5 hosts")
    val app = AppDefinition()
    val tasks = 0.to(9).map(num => makeSampleTask(s"$num", Map("rack" -> "rack-1", "color" -> "blue")))

    When("10 tasks should be selected to kill")
    val result = Constraints.selectTasksToKill(app, tasks, 5)

    Then("0 tasks got selected")
    result should have size 0
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

    var sameRack = Iterable.empty[Task]

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

    var groupRack = Iterable.empty[Task]

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

    var groupHost = Iterable.empty[Task]
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

  def makeSampleTask(id: String, attrs: Map[String, String]) = {
    val attributes = attrs.map { case (name, value) => TextAttribute(name, value): Attribute }
    MarathonTestHelper.stagedTask(id)
      .withAgentInfo(_.copy(attributes = attributes))
      .withHostPorts(Seq(999))
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
    MarathonTestHelper
      .runningTask(id)
      .withAgentInfo(_.copy(host = host))
      .withHostPorts(Seq(999))
  }

  def makeConstraint(field: String, operator: Operator, value: String) = {
    Constraint.newBuilder
      .setField(field)
      .setOperator(operator)
      .setValue(value)
      .build
  }

}
