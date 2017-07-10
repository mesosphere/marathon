package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon._
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.{ FrameworkID, OfferID, SlaveID, TextAttribute }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{ Attribute, Offer }

import scala.util.Random

class ConstraintsTest extends UnitTest {

  import mesosphere.mesos.protos.Implicits._

  "Constraints" should {
    "Select tasks to kill for a single group by works" in {
      Given("app with hostname group_by and 20 tasks even distributed on 2 hosts")
      val app = AppDefinition(id = PathId("/test"), constraints = Set(makeConstraint("hostname", Operator.GROUP_BY, "")))
      val tasks = 0.to(19).map(num => makeInstanceWithHost(app.id, s"${num % 2}"))

      When("10 tasks should be selected to kill")
      val result = Constraints.selectInstancesToKill(app, tasks, 10)

      Then("10 tasks got selected and evenly distributed")
      result should have size 10
      val dist = result.groupBy(_.agentInfo.host.toInt % 2 == 1)
      dist should have size 2
      dist.values.head should have size 5
    }

    "Select only tasks to kill for an unbalanced distribution" in {
      Given("app with hostname group_by and 30 tasks uneven distributed on 2 hosts")
      val app = AppDefinition(id = PathId("/test"), constraints = Set(makeConstraint("hostname", Operator.GROUP_BY, "")))
      val tasks = 0.to(19).map(num => makeInstanceWithHost(app.id, "1")) ++
        20.to(29).map(num => makeInstanceWithHost(app.id, "2"))

      When("10 tasks should be selected to kill")
      val result = Constraints.selectInstancesToKill(app, tasks, 10)

      Then("All 10 tasks are from srv1")
      result should have size 10
      result.forall(_.agentInfo.host == "1") should be(true)
    }

    "Select tasks to kill for multiple group by works" in {
      Given("app with 2 group_by distributions and 40 tasks even distributed")
      val app = AppDefinition(
        id = PathId("/test"),
        constraints = Set(
          makeConstraint("rack", Operator.GROUP_BY, ""),
          makeConstraint("color", Operator.GROUP_BY, "")))
      val tasks =
        0.to(9).map(num => makeSampleInstanceWithTextAttrs(app.id, Map("rack" -> "rack-1", "color" -> "blue"))) ++
          10.to(19).map(num => makeSampleInstanceWithTextAttrs(app.id, Map("rack" -> "rack-1", "color" -> "green"))) ++
          20.to(29).map(num => makeSampleInstanceWithTextAttrs(app.id, Map("rack" -> "rack-2", "color" -> "blue"))) ++
          30.to(39).map(num => makeSampleInstanceWithTextAttrs(app.id, Map("rack" -> "rack-2", "color" -> "green")))

      When("20 tasks should be selected to kill")
      val result = Constraints.selectInstancesToKill(app, tasks, 20)

      Then("20 tasks got selected and evenly distributed")
      result should have size 20
      result.count(_.agentInfo.attributes.exists(_.getText.getValue == "rack-1")) should be(10)
      result.count(_.agentInfo.attributes.exists(_.getText.getValue == "rack-2")) should be(10)
      result.count(_.agentInfo.attributes.exists(_.getText.getValue == "blue")) should be(10)
      result.count(_.agentInfo.attributes.exists(_.getText.getValue == "green")) should be(10)
    }

    "Does not select any task without constraint" in {
      Given("app with hostname group_by and 10 tasks even distributed on 5 hosts")
      val app = AppDefinition(id = PathId("/test"))
      val tasks = 0.to(9).map(num => makeSampleInstanceWithTextAttrs(app.id, Map("rack" -> "rack-1", "color" -> "blue")))

      When("10 tasks should be selected to kill")
      val result = Constraints.selectInstancesToKill(app, tasks, 5)

      Then("0 tasks got selected")
      result should have size 0
    }

    "UniqueHostConstraint" in {
      val appId = PathId("/test")
      val task1_host1 = makeInstanceWithHost(appId, "host1")
      val task2_host2 = makeInstanceWithHost(appId, "host2")
      val task3_host3 = makeInstanceWithHost(appId, "host3")
      val attributes = Seq.empty[Attribute]

      val firstTask = Seq.empty[Placed]

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

      val differentHosts = Seq(task1_host1, task2_host2, task3_host3)

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

    "RackConstraints" in {
      val appId = PathId("/test")
      val task1_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task2_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task3_rack2 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-2"))

      val freshRack = Seq.empty[Instance]
      val sameRack = Seq(task1_rack1, task2_rack1)
      val uniqueRack = Seq(task1_rack1, task3_rack2)

      val clusterByRackId = makeConstraint("rackid", Constraint.Operator.CLUSTER, "")
      val uniqueRackId = makeConstraint("rackid", Constraint.Operator.UNIQUE, "")

      val clusterFreshRackMet = Constraints.meetsConstraint(
        freshRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        clusterByRackId)

      assert(clusterFreshRackMet, "Should be able to schedule in fresh rack.")

      val clusterRackMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        clusterByRackId)
      assert(clusterRackMet, "Should meet clustered-in-rack constraints.")

      val clusterRackNotMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
        clusterByRackId)

      assert(!clusterRackNotMet, "Should not meet cluster constraint.")

      val clusterNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack,
        makeOffer("foohost", Seq.empty),
        clusterByRackId)

      assert(!clusterNoAttributeNotMet, "Should not meet cluster constraint.")

      val uniqueFreshRackMet = Constraints.meetsConstraint(
        freshRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        uniqueRackId)

      assert(uniqueFreshRackMet, "Should meet unique constraint for fresh rack.")

      val uniqueRackMet = Constraints.meetsConstraint(
        uniqueRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-3"))),
        uniqueRackId)

      assert(uniqueRackMet, "Should meet unique constraint for rack")

      val uniqueRackNotMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        uniqueRackId)

      assert(!uniqueRackNotMet, "Should not meet unique constraint for rack.")

      val uniqueNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack,
        makeOffer("foohost", Seq.empty),
        uniqueRackId)

      assert(!uniqueNoAttributeNotMet, "Should not meet unique constraint.")
    }

    "AttributesLikeByConstraints" in {
      val appId = PathId("/test")
      val task1_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("foo" -> "bar"))
      val task2_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("jdk" -> "7"))
      val freshRack = Seq(task1_rack1, task2_rack1)
      val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.LIKE, "7")

      val likeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(TextAttribute("jdk", "6"))), // slave attributes
        jdk7Constraint)
      assert(!likeVersionNotMet, "Should not meet like-version constraints.")

      val likeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(TextAttribute("jdk", "7"))), // slave attributes
        jdk7Constraint)
      assert(likeVersionMet, "Should meet like-version constraints.")

      val likeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7Constraint)
      assert(!likeNoAttributeNotMet, "Should not meet like-no-attribute constraints.")
    }

    "AttributesUnlikeByConstraints" in {
      val appId = PathId("/test")
      val task1_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("foo" -> "bar"))
      val task2_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("jdk" -> "7"))
      val freshRack = Seq(task1_rack1, task2_rack1)
      val jdk7Constraint = makeConstraint("jdk", Constraint.Operator.UNLIKE, "7")

      val unlikeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(TextAttribute("jdk", "6"))), // slave attributes
        jdk7Constraint)
      assert(unlikeVersionMet, "Should meet unlike-version constraints.")

      val unlikeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(TextAttribute("jdk", "7"))), // slave attributes
        jdk7Constraint)
      assert(!unlikeVersionNotMet, "Should not meet unlike-version constraints.")

      val unlikeNoAttributeMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7Constraint)
      assert(unlikeNoAttributeMet, "Should meet unlike-no-attribute constraints.")
    }

    "RackGroupedByConstraints" in {
      val appId = PathId("/test")
      val task1_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task2_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task3_rack2 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-2"))
      val task4_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task5_rack3 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-3"))

      var sameRack = Seq.empty[Instance]

      val group2ByRack = makeConstraint("rackid", Constraint.Operator.GROUP_BY, "2")

      val groupByFreshRackMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        group2ByRack)

      assert(groupByFreshRackMet, "Should be able to schedule in fresh rack.")

      sameRack ++= Set(task1_rack1)

      val groupByRackMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        group2ByRack)

      assert(!groupByRackMet, "Should not meet group-by-rack constraints.")

      val groupByRackMet2 = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
        group2ByRack)

      assert(groupByRackMet2, "Should meet group-by-rack constraint.")

      sameRack ++= Set(task3_rack2)

      val groupByRackMet3 = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        group2ByRack)

      assert(groupByRackMet3, "Should meet group-by-rack constraints.")

      sameRack ++= Set(task2_rack1)

      val groupByRackNotMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        group2ByRack)

      assert(!groupByRackNotMet, "Should not meet group-by-rack constraint.")

      val groupByNoAttributeNotMet = Constraints.meetsConstraint(
        sameRack,
        makeOffer("foohost", Seq.empty),
        group2ByRack)
      assert(!groupByNoAttributeNotMet, "Should not meet group-by-no-attribute constraints.")
    }

    "RackGroupedByConstraints2" in {
      val appId = PathId("/test")
      val task1_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task2_rack2 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-2"))
      val task3_rack3 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-3"))
      val task4_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-1"))
      val task5_rack2 = makeSampleInstanceWithTextAttrs(appId, Map("rackid" -> "rack-2"))

      var groupRack = Seq.empty[Instance]

      val groupByRack = makeConstraint("rackid", Constraint.Operator.GROUP_BY, "3")

      val clusterFreshRackMet = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        groupByRack)

      assert(clusterFreshRackMet, "Should be able to schedule in fresh rack.")

      groupRack ++= Set(task1_rack1)

      val clusterRackMet1 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
        groupByRack)

      assert(clusterRackMet1, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(task2_rack2)

      val clusterRackMet2 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-3"))),
        groupByRack)

      assert(clusterRackMet2, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(task3_rack3)

      val clusterRackMet3 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-1"))),
        groupByRack)

      assert(clusterRackMet3, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(task4_rack1)

      val clusterRackMet4 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
        groupByRack)

      assert(clusterRackMet4, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(task5_rack2)

      val clusterRackMet5 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), TextAttribute("rackid", "rack-2"))),
        groupByRack)

      assert(!clusterRackMet5, "Should not meet clustered-in-rack constraints.")
    }

    "RackGroupedByConstraints3" in {
      val appId = PathId("/test")
      val instance1_rack1 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 1.0))
      val instance2_rack2 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 2.0))
      val instance3_rack3 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 3.0))
      val instance4_rack1 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 1.0))
      val instance5_rack2 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 2.0))

      var groupRack = Seq.empty[Instance]

      val groupByRack = makeConstraint("rackid", Constraint.Operator.GROUP_BY, "3")

      val clusterFreshRackMet = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 1))),
        groupByRack)

      assert(clusterFreshRackMet, "Should be able to schedule in fresh rack.")

      groupRack ++= Set(instance1_rack1)

      val clusterRackMet1 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 2))),
        groupByRack)

      assert(clusterRackMet1, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(instance2_rack2)

      val clusterRackMet2 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 3))),
        groupByRack)

      assert(clusterRackMet2, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(instance3_rack3)

      val clusterRackMet3 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 1))),
        groupByRack)

      assert(clusterRackMet3, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(instance4_rack1)

      val clusterRackMet4 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 2))),
        groupByRack)

      assert(clusterRackMet4, "Should meet clustered-in-rack constraints.")

      groupRack ++= Set(instance5_rack2)

      val clusterRackMet5 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 2))),
        groupByRack)

      assert(!clusterRackMet5, "Should not meet clustered-in-rack constraints.")
    }

    "HostnameGroupedByConstraints" in {
      val appId = PathId("/test")
      val task1_host1 = makeInstanceWithHost(appId, "host1")
      val task2_host1 = makeInstanceWithHost(appId, "host1")
      val task3_host2 = makeInstanceWithHost(appId, "host2")
      val task4_host3 = makeInstanceWithHost(appId, "host3")

      var groupHost = Seq.empty[Instance]
      val attributes = Seq.empty[Attribute]

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

    "HostnameMaxPerConstraints" in {
      val appId = PathId("/test")
      val task1_host1 = makeInstanceWithHost(appId, "host1")
      val task2_host1 = makeInstanceWithHost(appId, "host1")
      val task3_host2 = makeInstanceWithHost(appId, "host2")
      val task4_host3 = makeInstanceWithHost(appId, "host3")

      var groupHost = Seq.empty[Instance]
      val attributes = Seq.empty[Attribute]

      val maxPerHost = makeConstraint("hostname", Constraint.Operator.MAX_PER, "2")

      val maxPerFreshHostMet = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host1", attributes),
        maxPerHost)

      assert(maxPerFreshHostMet, "Should be able to schedule in fresh host.")

      groupHost ++= Set(task1_host1)

      val maxPerHostMet = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host1", attributes),
        maxPerHost)

      assert(maxPerHostMet, "Should meet max-per-host constraint.")

      groupHost ++= Set(task2_host1)

      val maxPerHostMet2 = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host2", attributes),
        maxPerHost)

      assert(maxPerHostMet2, "Should meet max-per-host constraint.")

      groupHost ++= Set(task3_host2)

      val maxPerHostMet3 = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host1", attributes),
        maxPerHost)

      assert(!maxPerHostMet3, "Should not meet max-per-host constraint.")

      groupHost ++= Set(task2_host1)

      val maxPerHostMet4 = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host3", attributes),
        maxPerHost)

      assert(maxPerHostMet4, "Should meet max-per-host constraint.")
    }

    "RackMaxPerConstraints" in {
      val appId = PathId("/test")
      val instance1_rack1 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 1.0))
      val instance2_rack2 = makeSampleInstanceWithScalarAttrs(appId, Map("rackid" -> 2.0))

      var groupRack = Seq.empty[Instance]

      val groupByRack = makeConstraint("rackid", Constraint.Operator.MAX_PER, "2")

      val maxPerFreshRackMet1 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 1))),
        groupByRack)

      assert(maxPerFreshRackMet1, "Should be able to schedule in fresh rack 1.")

      groupRack ++= Set(instance1_rack1)

      val maxPerFreshRackMet2 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 2))),
        groupByRack)

      assert(maxPerFreshRackMet2, "Should be able to schedule in fresh rack 2.")

      groupRack ++= Set(instance2_rack2)

      val maxPerRackMet3 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 1))),
        groupByRack)

      assert(maxPerRackMet3, "Should be able to schedule in rack 1.")

      groupRack ++= Set(instance1_rack1)

      val maxPerRackNotMet4 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", Seq(TextAttribute("foo", "bar"), makeScalarAttribute("rackid", 1))),
        groupByRack)

      assert(!maxPerRackNotMet4, "Should not meet max_per constraint on rack 1.")
    }

    "AttributesTypes" in {
      val appId = PathId("/test")
      val instance1_rack1 = makeSampleInstanceWithTextAttrs(appId, Map("foo" -> "bar"))
      val instance2_rack1 = makeSampleInstanceWithScalarAttrs(appId, Map("jdk" -> 7))
      val instance3_rack1 = makeSampleInstanceWithRangeAttrs(appId, Map("jdk" -> ((6L, 7L))))
      val instance4_rack1 = makeSampleInstanceWithSetAttrs(appId, Map("gpu" -> List("0", "1")))
      val freshRack = Seq(instance1_rack1, instance2_rack1, instance3_rack1, instance4_rack1)
      val jdk7ConstraintLike = makeConstraint("jdk", Constraint.Operator.LIKE, "7")
      val jdk7ConstraintUnlike = makeConstraint("jdk", Constraint.Operator.UNLIKE, "7")
      val jdk7ConstraintCluster = makeConstraint("jdk", Constraint.Operator.CLUSTER, "7")
      val jdk7ConstraintLikeRange = makeConstraint("jdk", Constraint.Operator.LIKE, "\\[6-7\\]")
      val jdk7ConstraintUnlikeRange = makeConstraint("jdk", Constraint.Operator.UNLIKE, "\\[6-7\\]")
      val jdk7ConstraintClusterRange = makeConstraint("jdk", Constraint.Operator.CLUSTER, "[6-7]")
      val jdk7ConstraintLikeSet = makeConstraint("gpu", Constraint.Operator.LIKE, "\\{0,1\\}")
      val jdk7ConstraintUnlikeSet = makeConstraint("gpu", Constraint.Operator.UNLIKE, "\\{0,1\\}")
      val jdk7ConstraintClusterSet = makeConstraint("gpu", Constraint.Operator.CLUSTER, "{0,1}")

      val likeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeScalarAttribute("jdk", 6))), // slave attributes
        jdk7ConstraintLike)
      assert(!likeVersionNotMet, "Should not meet like-version constraints.")

      val likeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeScalarAttribute("jdk", 7))), // slave attributes
        jdk7ConstraintLike)
      assert(likeVersionMet, "Should meet like-version constraints.")

      val clusterVersionNotMet = Constraints.meetsConstraint(
        Seq(instance2_rack1), // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeScalarAttribute("jdk", 6))), // slave attributes
        jdk7ConstraintCluster)
      assert(!clusterVersionNotMet, "Should not meet cluster-version constraints.")

      val clusterVersionMet = Constraints.meetsConstraint(
        Seq(instance2_rack1), // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeScalarAttribute("jdk", 7))), // slave attributes
        jdk7ConstraintCluster)
      assert(clusterVersionMet, "Should meet cluster-version constraints.")

      val likeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7ConstraintLike)
      assert(!likeNoAttributeNotMet, "Should not meet like-no-attribute constraints.")

      val unlikeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlike
        makeOffer("foohost", Seq(makeScalarAttribute("jdk", 7))), // slave attributes
        jdk7ConstraintUnlike)
      assert(!unlikeVersionNotMet, "Should not meet unlike-version constraints.")

      val unlikeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlike
        makeOffer("foohost", Seq(makeScalarAttribute("jdk", 6))), // slave attributes
        jdk7ConstraintUnlike)
      assert(unlikeVersionMet, "Should meet unlike-version constraints.")

      val unlikeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlike
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7ConstraintUnlike)
      assert(unlikeNoAttributeNotMet, "Should meet unlike-no-attribute constraints.")

      val likeRangeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeRangeAttribute("jdk", 5, 7))), // slave attributes
        jdk7ConstraintLikeRange)
      assert(!likeRangeVersionNotMet, "Should not meet like-range-version constraints.")

      val likeRangeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeRangeAttribute("jdk", 6, 7))), // slave attributes
        jdk7ConstraintLikeRange)
      assert(likeRangeVersionMet, "Should meet like-range-version constraints.")

      val clusterRangeVersionNotMet = Constraints.meetsConstraint(
        Seq(instance3_rack1), // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeRangeAttribute("jdk", 5, 7))), // slave attributes
        jdk7ConstraintClusterRange)
      assert(!clusterRangeVersionNotMet, "Should not meet cluster-range-version constraints.")

      val clusterRangeVersionMet = Constraints.meetsConstraint(
        Seq(instance3_rack1), // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeRangeAttribute("jdk", 6, 7))), // slave attributes
        jdk7ConstraintClusterRange)
      assert(clusterRangeVersionMet, "Should meet cluster-range-version constraints.")

      val likeRangeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7ConstraintLikeRange)
      assert(!likeRangeNoAttributeNotMet, "Should not meet like-range-no-attribute constraints.")

      val unlikeRangeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeRange
        makeOffer("foohost", Seq(makeRangeAttribute("jdk", 6, 7))), // slave attributes
        jdk7ConstraintUnlikeRange)
      assert(!unlikeRangeVersionNotMet, "Should not meet unlike-range-version constraints.")

      val unlikeRangeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeRange
        makeOffer("foohost", Seq(makeRangeAttribute("jdk", 5, 7))), // slave attributes
        jdk7ConstraintUnlikeRange)
      assert(unlikeRangeVersionMet, "Should meet unlike-range-version constraints.")

      val unlikeRangeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeRange
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7ConstraintUnlikeRange)
      assert(unlikeRangeNoAttributeNotMet, "Should meet unlike-range-no-attribute constraints.")

      val likeSetVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeSetAttribute("gpu", List("2", "1")))), // slave attributes
        jdk7ConstraintLikeSet)
      assert(!likeSetVersionNotMet, "Should not meet like-set-version constraints.")

      val likeSetVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeSetAttribute("gpu", List("1", "0")))), // slave attributes
        jdk7ConstraintLikeSet)
      assert(likeSetVersionMet, "Should meet like-set-version constraints.")

      val clusterSetVersionNotMet = Constraints.meetsConstraint(
        Seq(instance4_rack1), // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeSetAttribute("jdk", List("2", "1")))), // slave attributes
        jdk7ConstraintClusterSet)
      assert(!clusterSetVersionNotMet, "Should not meet cluster-set-version constraints.")

      val clusterSetVersionMet = Constraints.meetsConstraint(
        Seq(instance4_rack1), // list of tasks register in the cluster
        makeOffer("foohost", Seq(makeSetAttribute("gpu", List("0", "1")))), // slave attributes
        jdk7ConstraintClusterSet)
      assert(clusterSetVersionMet, "Should meet cluster-set-version constraints.")

      val likeSetNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7ConstraintLikeSet)
      assert(!likeSetNoAttributeNotMet, "Should not meet like-set-no-attribute constraints.")

      val unlikeSetVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeset
        makeOffer("foohost", Seq(makeSetAttribute("gpu", List("0", "1")))), // slave attributes
        jdk7ConstraintUnlikeSet)
      assert(!unlikeSetVersionNotMet, "Should not meet unlike-set-version constraints.")

      val unlikeSetVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeset
        makeOffer("foohost", Seq(makeSetAttribute("gpu", List("1", "2")))), // slave attributes
        jdk7ConstraintUnlikeSet)
      assert(unlikeSetVersionMet, "Should meet unlike-set-version constraints.")

      val unlikeSetNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeset
        makeOffer("foohost", Seq.empty), // no slave attribute
        jdk7ConstraintUnlikeSet)
      assert(unlikeSetNoAttributeNotMet, "Should meet unlike-set-no-attribute constraints.")
    }
  }
  private def makeSampleInstanceWithTextAttrs(runSpecId: PathId, attrs: Map[String, String]): Instance = {
    val attributes: Seq[Attribute] = attrs.map {
      case (name, value) =>
        TextAttribute(name, value): Attribute
    }(collection.breakOut)
    TestInstanceBuilder.newBuilder(runSpecId).addTaskWithBuilder().taskStaged()
      .withNetworkInfo(hostPorts = Seq(999))
      .build()
      .withAgentInfo(attributes = Some(attributes))
      .getInstance()
  }

  private def makeScalarAttribute(name: String, value: Double) = {
    Protos.Attribute.newBuilder
      .setType(Protos.Value.Type.SCALAR)
      .setName(name)
      .setScalar(Protos.Value.Scalar.newBuilder.setValue(value))
      .build
  }

  private def makeSampleInstanceWithScalarAttrs(runSpecId: PathId, attrs: Map[String, Double]): Instance = {
    val attributes: Seq[Attribute] = attrs.map {
      case (name, value) =>
        makeScalarAttribute(name, value)
    }(collection.breakOut)
    TestInstanceBuilder.newBuilder(runSpecId).addTaskWithBuilder().taskStaged()
      .withNetworkInfo(hostPorts = Seq(999))
      .build()
      .withAgentInfo(attributes = Some(attributes))
      .getInstance()
  }

  private def makeRangeAttribute(name: String, begin: Long, end: Long) = {
    Protos.Attribute.newBuilder
      .setType(Protos.Value.Type.RANGES)
      .setName(name)
      .setRanges(
        Protos.Value.Ranges.newBuilder().addRange(
          Protos.Value.Range.newBuilder()
            .setBegin(begin)
            .setEnd(end)
            .build()
        ).build()
      ).build
  }

  private def makeSampleInstanceWithRangeAttrs(runSpecId: PathId, attrs: Map[String, (Long, Long)]): Instance = {
    val attributes: Seq[Attribute] = attrs.map {
      case (name, (begin, end)) =>
        makeRangeAttribute(name, begin, end)
    }(collection.breakOut)
    TestInstanceBuilder.newBuilder(runSpecId).addTaskWithBuilder().taskStaged()
      .withNetworkInfo(hostPorts = Seq(999))
      .build()
      .withAgentInfo(attributes = Some(attributes))
      .getInstance()
  }

  private def makeSetAttribute(name: String, items: List[String]) = {
    Protos.Attribute.newBuilder
      .setType(Protos.Value.Type.SET)
      .setName(name)
      .setSet(Protos.Value.Set.newBuilder
        .addAllItem(items.asJava)
        .build()
      )
      .build
  }

  private def makeSampleInstanceWithSetAttrs(runSpecId: PathId, attrs: Map[String, List[String]]): Instance = {
    val attributes: Seq[Attribute] = attrs.map {
      case (name, value) =>
        makeSetAttribute(name, value)
    }(collection.breakOut)
    TestInstanceBuilder.newBuilder(runSpecId).addTaskWithBuilder().taskStaged()
      .withNetworkInfo(hostPorts = Seq(999))
      .build()
      .withAgentInfo(attributes = Some(attributes))
      .getInstance()
  }

  private def makeOffer(hostname: String, attributes: Seq[Attribute]) = {
    Offer.newBuilder
      .setId(OfferID(Random.nextString(9)))
      .setSlaveId(SlaveID(Random.nextString(9)))
      .setFrameworkId(FrameworkID(Random.nextString(9)))
      .setHostname(hostname)
      .addAllAttributes(attributes.asJava)
      .build
  }

  private def makeInstanceWithHost(appId: PathId, host: String): Instance = {
    TestInstanceBuilder.newBuilder(appId).addTaskWithBuilder().taskRunning()
      .withNetworkInfo(hostName = Some(host), hostPorts = Seq(999))
      .build()
      .withAgentInfo(hostName = Some(host))
      .getInstance()
  }

  private def makeConstraint(field: String, operator: Operator, value: String) = {
    Constraint.newBuilder
      .setField(field)
      .setOperator(operator)
      .setValue(value)
      .build
  }
}
