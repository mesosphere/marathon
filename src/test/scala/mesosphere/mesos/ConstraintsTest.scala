package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.Constraint.Operator.{ IS, LIKE, UNLIKE, CLUSTER, GROUP_BY, MAX_PER, UNIQUE }
import mesosphere.marathon._
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.{ FrameworkID, OfferID, SlaveID }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{ DomainInfo, Offer }
import org.scalatest.matchers.{ MatchResult, Matcher }

import scala.util.Random

class ConstraintsTest extends UnitTest {
  import mesosphere.mesos.protos.Implicits._
  import Constraints.{ zoneField, regionField, hostnameField }

  val rackIdField = "rackid"
  val jdkField = "jdk"

  case class meetConstraint(field: String, operator: Operator, value: String, placed: Seq[Placed] = Nil) extends Matcher[Offer] {
    override def apply(offer: Offer): MatchResult = {
      val constraint = makeConstraint(field, operator, value)
      val matched = Constraints.meetsConstraint(placed, offer, constraint)
      val (offerReader, placedReader) = Constraints.readerForField(field)
      val offerValue = offerReader(offer)
      val placedValues = placed map placedReader
      val description = s"""Constraint: ${field}:${operator}:${value}
                           |Offer value: ${offerValue}
                           |Placed values: ${placedValues.mkString(", ")}""".stripMargin.trim
      MatchResult(
        matched,
        s"Offer did not match constraint\n${description}",
        s"Offer matched constraint\n${description}")
    }

    def withPlacements(newPlaced: Placed*): meetConstraint = copy(placed = newPlaced.toList)
  }

  "Constraints" should {
    "Select tasks to kill for a single group by works" in {
      Given("app with hostname group_by and 20 tasks even distributed on 2 hosts")
      val app = AppDefinition(id = PathId("/test"), constraints = Set(makeConstraint(hostnameField, GROUP_BY, "")))
      val tasks = 0.to(19).map(num => makeInstance(app.id, host = s"${num % 2}"))

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
      val app = AppDefinition(id = PathId("/test"), constraints = Set(makeConstraint(hostnameField, GROUP_BY, "")))
      val tasks = 0.to(19).map(num => makeInstance(app.id, host = "1")) ++
        20.to(29).map(num => makeInstance(app.id, host = "2"))

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
          makeConstraint("rack", GROUP_BY, ""),
          makeConstraint("color", GROUP_BY, "")))
      val tasks =
        0.to(9).map(num => makeInstance(app.id, attributes = "rack:rack-1;color:blue")) ++
          10.to(19).map(num => makeInstance(app.id, attributes = "rack:rack-1;color:green")) ++
          20.to(29).map(num => makeInstance(app.id, attributes = "rack:rack-2;color:blue")) ++
          30.to(39).map(num => makeInstance(app.id, attributes = "rack:rack-2;color:green"))

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

      val tasks = 0.to(9).map(num => makeInstance(app.id, attributes = "rack:rack-1;color:blue"))

      When("10 tasks should be selected to kill")
      val result = Constraints.selectInstancesToKill(app, tasks, 5)

      Then("0 tasks got selected")
      result should have size 0
    }

    "UniqueHostConstraint" in {
      val appId = PathId("/test")
      val task1_host1 = makeInstance(appId, host = "host1")
      val task2_host2 = makeInstance(appId, host = "host2")
      val task3_host3 = makeInstance(appId, host = "host3")

      makeOffer("foohost") should meetConstraint(
        hostnameField, CLUSTER, "").withPlacements()

      makeOffer("wrong.com") shouldNot meetConstraint(
        hostnameField, CLUSTER, "right.com").withPlacements()

      // First placement
      makeOffer("host2") should meetConstraint(
        hostnameField, UNIQUE, "").withPlacements()

      makeOffer("host4") should meetConstraint(
        hostnameField, UNIQUE, "").withPlacements(task1_host1, task2_host2, task3_host3)

      makeOffer("host2") shouldNot meetConstraint(
        hostnameField, UNIQUE, "").withPlacements(task1_host1, task2_host2, task3_host3)
    }

    "Generic property constraints" in {
      val appId = PathId("/test")
      val task1_rack1 = makeInstance(appId, attributes = "rackid:rack-1")
      val task2_rack1 = makeInstance(appId, attributes = "rackid:rack-1")
      val task3_rack2 = makeInstance(appId, attributes = "rackid:rack-2")

      val offerRack1 = makeOffer("foohost", "foo:bar;rackid:rack-1")
      val offerRack2 = makeOffer("foohost", "foo:bar;rackid:rack-2")
      val offerRack3 = makeOffer("foohost", "foo:bar;rackid:rack-3")

      // Should be able to schedule in a fresh rack
      offerRack1 should meetConstraint(
        "rackid", CLUSTER, "").withPlacements()

      // Should schedule along with same rack
      offerRack1 should meetConstraint(
        "rackid", CLUSTER, "").withPlacements(task1_rack1, task2_rack1)

      // rack2 offer cant match if there are rack1 placements
      offerRack2 shouldNot meetConstraint(
        "rackid", CLUSTER, "").withPlacements(task1_rack1, task2_rack1)

      // If the offer is missing the attribute targetted, then it should not match if no placements yet
      makeOffer("foohost") shouldNot meetConstraint("rackid", CLUSTER, "").withPlacements()

      // Should meet unique constraint for fresh rack.
      offerRack1 should meetConstraint("rackid", UNIQUE, "").withPlacements()

      offerRack3 should meetConstraint("rackid", UNIQUE, "").withPlacements(task1_rack1, task3_rack2)

      offerRack1 shouldNot meetConstraint("rackid", UNIQUE, "").withPlacements(task1_rack1, task3_rack2)

      // If the offer is missing the attribute targetted, do not match (even if no placements yet)
      makeOffer("foohost") shouldNot meetConstraint("rackid", UNIQUE, "").withPlacements()
    }

    "AttributesLikeByConstraints" in {
      val jdk6Offer = makeOffer("foohost", s"${jdkField}:6")
      val jdk7Offer = makeOffer("foohost", s"${jdkField}:7")

      jdk6Offer shouldNot meetConstraint(jdkField, LIKE, "7")
      jdk7Offer should meetConstraint(jdkField, LIKE, "7")
      makeOffer("foohost") shouldNot meetConstraint(jdkField, LIKE, "7")
    }

    "AttributesUnlikeByConstraints" in {
      val jdk6Offer = makeOffer("foohost", s"${jdkField}:6")
      val jdk7Offer = makeOffer("foohost", s"${jdkField}:7")

      jdk6Offer should meetConstraint(jdkField, UNLIKE, "7")
      jdk7Offer shouldNot meetConstraint(jdkField, UNLIKE, "7")

      // UNLIKE should match if offer is missing field
      makeOffer("foohost") should meetConstraint(jdkField, UNLIKE, "7")
    }

    "RackGroupedByConstraints" in {
      val appId = PathId("/test")
      val task1_rack1 = makeInstance(appId, attributes = s"${rackIdField}:rack-1")
      val task2_rack1 = makeInstance(appId, attributes = s"${rackIdField}:rack-1")
      val task3_rack2 = makeInstance(appId, attributes = s"${rackIdField}:rack-2")

      val rack1Offer = makeOffer("foohost", s"foo:bar;${rackIdField}:rack-1")
      val rack2Offer = makeOffer("foohost", s"foo:bar;${rackIdField}:rack-2")

      rack1Offer should meetConstraint(rackIdField, GROUP_BY, "2")
        .withPlacements()

      rack1Offer shouldNot meetConstraint(rackIdField, GROUP_BY, "2")
        .withPlacements(task1_rack1)

      rack2Offer should meetConstraint(rackIdField, GROUP_BY, "2")
        .withPlacements(task1_rack1)

      rack1Offer should meetConstraint(rackIdField, GROUP_BY, "2")
        .withPlacements(task1_rack1, task3_rack2)

      rack1Offer shouldNot meetConstraint(rackIdField, GROUP_BY, "2")
        .withPlacements(task1_rack1, task2_rack1, task3_rack2)

      // Should not meet group-by-no-attribute constraints.
      makeOffer("foohost") shouldNot meetConstraint(rackIdField, GROUP_BY, "2")
    }

    "RackGroupedByConstraints2" in {
      val appId = PathId("/test")
      val task1_rack1 = makeInstance(appId, attributes = s"${rackIdField}:rack-1")
      val task2_rack2 = makeInstance(appId, attributes = s"${rackIdField}:rack-2")
      val task3_rack3 = makeInstance(appId, attributes = s"${rackIdField}:rack-3")
      val task4_rack1 = makeInstance(appId, attributes = s"${rackIdField}:rack-1")
      val task5_rack2 = makeInstance(appId, attributes = s"${rackIdField}:rack-2")
      val rack1Offer = makeOffer("foohost", s"foo:bar;${rackIdField}:rack-1")
      val rack2Offer = makeOffer("foohost", s"foo:bar;${rackIdField}:rack-2")
      val rack3Offer = makeOffer("foohost", s"foo:bar;${rackIdField}:rack-3")

      rack1Offer should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements()

      rack2Offer should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(task1_rack1)

      rack3Offer should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(task1_rack1, task2_rack2)

      rack1Offer should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(task1_rack1, task2_rack2, task3_rack3)

      rack2Offer should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(task1_rack1, task2_rack2, task3_rack3, task4_rack1)

      rack2Offer shouldNot meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(task1_rack1, task2_rack2, task3_rack3, task4_rack1, task5_rack2)
    }

    "RackGroupedByConstraints3" in {
      val appId = PathId("/test")
      val instance_rack1 = makeInstance(appId, attributes = s"${rackIdField}:1.0")
      val instance_rack2 = makeInstance(appId, attributes = s"${rackIdField}:2.0")
      val instance_rack3 = makeInstance(appId, attributes = s"${rackIdField}:3.0")

      val offer_rack1 = makeOffer("foohost", s"foo:bar;${rackIdField}:1")
      val offer_rack2 = makeOffer("foohost", s"foo:bar;${rackIdField}:2")
      val offer_rack3 = makeOffer("foohost", s"foo:bar;${rackIdField}:3")

      offer_rack1 should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements()

      offer_rack2 should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(instance_rack1)

      offer_rack3 should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(instance_rack1, instance_rack2)

      offer_rack1 should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(instance_rack1, instance_rack2, instance_rack3)

      offer_rack2 should meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(instance_rack1, instance_rack2, instance_rack3,
        instance_rack1)

      offer_rack2 shouldNot meetConstraint(
        rackIdField, GROUP_BY, "3").withPlacements(instance_rack1, instance_rack2, instance_rack3,
        instance_rack1, instance_rack2)
    }

    "HostnameGroupedByConstraints" in {
      val appId = PathId("/test")
      val task1_host1 = makeInstance(appId, host = "host1")
      val task2_host1 = makeInstance(appId, host = "host1")
      val task3_host2 = makeInstance(appId, host = "host2")
      val task4_host3 = makeInstance(appId, host = "host3")

      var groupHost = Seq.empty[Instance]
      val attributes = ""

      val groupByHost = makeConstraint(hostnameField, GROUP_BY, "2")

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
      val task1_host1 = makeInstance(appId, host = "host1")
      val task2_host1 = makeInstance(appId, host = "host1")
      val task3_host2 = makeInstance(appId, host = "host2")

      var groupHost = Seq.empty[Instance]

      val maxPerHost = makeConstraint(hostnameField, MAX_PER, "2")

      val maxPerFreshHostMet = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host1"),
        maxPerHost)

      assert(maxPerFreshHostMet, "Should be able to schedule in fresh host.")

      groupHost ++= Set(task1_host1)

      val maxPerHostMet = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host1"),
        maxPerHost)

      assert(maxPerHostMet, "Should meet max-per-host constraint.")

      groupHost ++= Set(task2_host1)

      val maxPerHostMet2 = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host2"),
        maxPerHost)

      assert(maxPerHostMet2, "Should meet max-per-host constraint.")

      groupHost ++= Set(task3_host2)

      val maxPerHostMet3 = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host1"),
        maxPerHost)

      assert(!maxPerHostMet3, "Should not meet max-per-host constraint.")

      groupHost ++= Set(task2_host1)

      val maxPerHostMet4 = Constraints.meetsConstraint(
        groupHost,
        makeOffer("host3"),
        maxPerHost)

      assert(maxPerHostMet4, "Should meet max-per-host constraint.")
    }

    "RackMaxPerConstraints" in {
      val appId = PathId("/test")
      val instance1_rack1 = makeInstance(appId, attributes = s"${rackIdField}:1.0")
      val instance2_rack2 = makeInstance(appId, attributes = s"${rackIdField}:2.0")

      var groupRack = Seq.empty[Instance]

      val groupByRack = makeConstraint(rackIdField, MAX_PER, "2")

      val maxPerFreshRackMet1 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", s"foo:bar;${rackIdField}:1"),
        groupByRack)

      assert(maxPerFreshRackMet1, "Should be able to schedule in fresh rack 1.")

      groupRack ++= Set(instance1_rack1)

      val maxPerFreshRackMet2 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", s"foo:bar;${rackIdField}:2"),
        groupByRack)

      assert(maxPerFreshRackMet2, "Should be able to schedule in fresh rack 2.")

      groupRack ++= Set(instance2_rack2)

      val maxPerRackMet3 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", s"foo:bar;${rackIdField}:1"),
        groupByRack)

      assert(maxPerRackMet3, "Should be able to schedule in rack 1.")

      groupRack ++= Set(instance1_rack1)

      val maxPerRackNotMet4 = Constraints.meetsConstraint(
        groupRack,
        makeOffer("foohost", s"foo:bar;${rackIdField}:1"),
        groupByRack)

      assert(!maxPerRackNotMet4, "Should not meet max_per constraint on rack 1.")
    }

    "AttributesTypes" in {
      val appId = PathId("/test")
      val instance1_rack1 = makeInstance(appId, attributes = "foo:bar")
      val instance2_rack1 = makeInstance(appId, attributes = "jdk:7")
      val instance3_rack1 = makeInstance(appId, attributes = "jdk:[6-7]")
      val instance4_rack1 = makeInstance(appId, attributes = "gpu:{0,1}")
      val freshRack = Seq(instance1_rack1, instance2_rack1, instance3_rack1, instance4_rack1)
      val jdk7ConstraintLike = makeConstraint("jdk", LIKE, "7")
      val jdk7ConstraintUnlike = makeConstraint("jdk", UNLIKE, "7")
      val jdk7ConstraintCluster = makeConstraint("jdk", CLUSTER, "7")
      val jdk7ConstraintLikeRange = makeConstraint("jdk", LIKE, "\\[6-7\\]")
      val jdk7ConstraintUnlikeRange = makeConstraint("jdk", UNLIKE, "\\[6-7\\]")
      val jdk7ConstraintClusterRange = makeConstraint("jdk", CLUSTER, "[6-7]")
      val jdk7ConstraintLikeSet = makeConstraint("gpu", LIKE, "\\{0,1\\}")
      val jdk7ConstraintUnlikeSet = makeConstraint("gpu", UNLIKE, "\\{0,1\\}")
      val jdk7ConstraintClusterSet = makeConstraint("gpu", CLUSTER, "{0,1}")

      val likeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", "jdk:6"), // slave attributes
        jdk7ConstraintLike)
      assert(!likeVersionNotMet, "Should not meet like-version constraints.")

      val likeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", "jdk:7"), // slave attributes
        jdk7ConstraintLike)
      assert(likeVersionMet, "Should meet like-version constraints.")

      val clusterVersionNotMet = Constraints.meetsConstraint(
        Seq(instance2_rack1), // list of tasks register in the cluster
        makeOffer("foohost", "jdk:6"), // slave attributes
        jdk7ConstraintCluster)
      assert(!clusterVersionNotMet, "Should not meet cluster-version constraints.")

      val clusterVersionMet = Constraints.meetsConstraint(
        Seq(instance2_rack1), // list of tasks register in the cluster
        makeOffer("foohost", "jdk:7"), // slave attributes
        jdk7ConstraintCluster)
      assert(clusterVersionMet, "Should meet cluster-version constraints.")

      val likeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", ""), // no slave attribute
        jdk7ConstraintLike)
      assert(!likeNoAttributeNotMet, "Should not meet like-no-attribute constraints.")

      val unlikeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlike
        makeOffer("foohost", "jdk:7"), // slave attributes
        jdk7ConstraintUnlike)
      assert(!unlikeVersionNotMet, "Should not meet unlike-version constraints.")

      val unlikeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlike
        makeOffer("foohost", "jdk:6"), // slave attributes
        jdk7ConstraintUnlike)
      assert(unlikeVersionMet, "Should meet unlike-version constraints.")

      val unlikeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlike
        makeOffer("foohost", ""), // no slave attribute
        jdk7ConstraintUnlike)
      assert(unlikeNoAttributeNotMet, "Should meet unlike-no-attribute constraints.")

      val likeRangeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", "jdk:[5-7]"), // slave attributes
        jdk7ConstraintLikeRange)
      assert(!likeRangeVersionNotMet, "Should not meet like-range-version constraints.")

      val likeRangeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", "jdk:[6-7]"), // slave attributes
        jdk7ConstraintLikeRange)
      assert(likeRangeVersionMet, "Should meet like-range-version constraints.")

      val clusterRangeVersionNotMet = Constraints.meetsConstraint(
        Seq(instance3_rack1), // list of tasks register in the cluster
        makeOffer("foohost", "jdk:[5-7]"), // slave attributes
        jdk7ConstraintClusterRange)
      assert(!clusterRangeVersionNotMet, "Should not meet cluster-range-version constraints.")

      val clusterRangeVersionMet = Constraints.meetsConstraint(
        Seq(instance3_rack1), // list of tasks register in the cluster
        makeOffer("foohost", "jdk:[6-7]"), // slave attributes
        jdk7ConstraintClusterRange)
      assert(clusterRangeVersionMet, "Should meet cluster-range-version constraints.")

      val likeRangeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", ""), // no slave attribute
        jdk7ConstraintLikeRange)
      assert(!likeRangeNoAttributeNotMet, "Should not meet like-range-no-attribute constraints.")

      val unlikeRangeVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeRange
        makeOffer("foohost", "jdk:[6-7]"), // slave attributes
        jdk7ConstraintUnlikeRange)
      assert(!unlikeRangeVersionNotMet, "Should not meet unlike-range-version constraints.")

      val unlikeRangeVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeRange
        makeOffer("foohost", "jdk:5-7"), // slave attributes
        jdk7ConstraintUnlikeRange)
      assert(unlikeRangeVersionMet, "Should meet unlike-range-version constraints.")

      val unlikeRangeNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeRange
        makeOffer("foohost", ""), // no slave attribute
        jdk7ConstraintUnlikeRange)
      assert(unlikeRangeNoAttributeNotMet, "Should meet unlike-range-no-attribute constraints.")

      val likeSetVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", "gpu:{2,1}"), // slave attributes
        jdk7ConstraintLikeSet)
      assert(!likeSetVersionNotMet, "Should not meet like-set-version constraints.")

      val likeSetVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", "gpu:{1,0}"), // slave attributes
        jdk7ConstraintLikeSet)
      assert(likeSetVersionMet, "Should meet like-set-version constraints.")

      val clusterSetVersionNotMet = Constraints.meetsConstraint(
        Seq(instance4_rack1), // list of tasks register in the cluster
        makeOffer("foohost", "jdk:{2,1}"), // slave attributes
        jdk7ConstraintClusterSet)
      assert(!clusterSetVersionNotMet, "Should not meet cluster-set-version constraints.")

      val clusterSetVersionMet = Constraints.meetsConstraint(
        Seq(instance4_rack1), // list of tasks register in the cluster
        makeOffer("foohost", "gpu:{0,1}"), // slave attributes
        jdk7ConstraintClusterSet)
      assert(clusterSetVersionMet, "Should meet cluster-set-version constraints.")

      val likeSetNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the cluster
        makeOffer("foohost", ""), // no slave attribute
        jdk7ConstraintLikeSet)
      assert(!likeSetNoAttributeNotMet, "Should not meet like-set-no-attribute constraints.")

      val unlikeSetVersionNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeset
        makeOffer("foohost", "gpu:{0,1}"), // slave attributes
        jdk7ConstraintUnlikeSet)
      assert(!unlikeSetVersionNotMet, "Should not meet unlike-set-version constraints.")

      val unlikeSetVersionMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeset
        makeOffer("foohost", "gpu:{1,2}"), // slave attributes
        jdk7ConstraintUnlikeSet)
      assert(unlikeSetVersionMet, "Should meet unlike-set-version constraints.")

      val unlikeSetNoAttributeNotMet = Constraints.meetsConstraint(
        freshRack, // list of tasks register in the unlikeset
        makeOffer("foohost", ""), // no slave attribute
        jdk7ConstraintUnlikeSet)
      assert(unlikeSetNoAttributeNotMet, "Should meet unlike-set-no-attribute constraints.")
    }

    "DomainInfo" should {
      "apply constraints when *region is specified" in {
        val regionConstraintUnique = makeConstraint(regionField, UNIQUE, "")
        val offerRegion1 = makeOffer(hostnameField, "", Some(MarathonTestHelper.newDomainInfo("region1", "zone")))
        val offerRegion2 = makeOffer(hostnameField, "", Some(MarathonTestHelper.newDomainInfo("region2", "zone")))
        val instanceOnRegion1 = makeInstance(PathId("/test"), host = hostnameField, region = Some("region1"), zone = Some("zone1"))

        Constraints.meetsConstraint(Seq(instanceOnRegion1), offerRegion1, regionConstraintUnique) shouldBe false
        Constraints.meetsConstraint(Seq(instanceOnRegion1), offerRegion2, regionConstraintUnique) shouldBe true
      }

      "apply constraints when *zone is specified" in {
        val zoneConstraintUnique = makeConstraint(zoneField, UNIQUE, "")
        val offerZone1 = makeOffer(hostnameField, "", Some(MarathonTestHelper.newDomainInfo("region", "zone1")))
        val offerZone2 = makeOffer(hostnameField, "", Some(MarathonTestHelper.newDomainInfo("region", "zone2")))
        val instanceOnZone1 = makeInstance(PathId("/test"), host = hostnameField, region = Some("region"), zone = Some("zone1"))

        Constraints.meetsConstraint(Seq(instanceOnZone1), offerZone1, zoneConstraintUnique) shouldBe false
        Constraints.meetsConstraint(Seq(instanceOnZone1), offerZone2, zoneConstraintUnique) shouldBe true
      }
    }
  }

  "IS operator" should {
    "require that a value match exactly" in {
      makeOffer("righthost.com") should meetConstraint(hostnameField, IS, "righthost.com")
      makeOffer("wronghost.com") should meetConstraint(hostnameField, IS, "wronghost.com")
    }

    "match scalars of the same value but different format" in {
      makeOffer("host", "number:1") should meetConstraint("number", IS, "1.0")
      makeOffer("host", "number:1.0") should meetConstraint("number", IS, "1")
    }

    "match scalars that differ in value by 0.001 or less" in {
      makeOffer("host", "number:1.0") should meetConstraint("number", IS, "1.0001")
      makeOffer("host", "number:1.0") should meetConstraint("number", IS, "1.0005")
      makeOffer("host", "number:1.0") shouldNot meetConstraint("number", IS, "1.00051")
      makeOffer("host", "number:1.0") shouldNot meetConstraint("number", IS, "1.001")
    }
  }

  /**
    * Parse a single attribute:value pair string expression, according to the spec described in
    * http://mesos.apache.org/documentation/latest/attributes-resources/
    */
  private def parseAttr(expression: String): Protos.Attribute = {
    val Array(name, value) = expression.split(':')
    val b = Protos.Attribute.newBuilder.setName(name)
    value match {
      case Constraints.MesosSetValue(itemsText) =>
        // Note - Mesos set attributes are actually not supported. https://issues.apache.org/jira/browse/MESOS-8150
        b.setType(Protos.Value.Type.SET)
        val setBuilder = Protos.Value.Set.newBuilder
        itemsText.split(',').foreach(setBuilder.addItem(_))
        b.setSet(setBuilder)
      case Constraints.MesosRangeValue(rangeText) =>
        b.setType(Protos.Value.Type.RANGES)
        val ranges = Protos.Value.Ranges.newBuilder
        rangeText.split(',').foreach { r =>
          val Array(start, end) = r.split('-')
          ranges.addRange(
            Protos.Value.Range.newBuilder
              .setBegin(start.toLong)
              .setEnd(end.toLong))
        }
        b.setRanges(ranges)
      case Constraints.MesosScalarValue(scalarText) =>
        b.setType(Protos.Value.Type.SCALAR)
        b.setScalar(Protos.Value.Scalar.newBuilder.setValue(scalarText.toDouble))
      case text =>
        b.setType(Protos.Value.Type.TEXT)
        b.setText(Protos.Value.Text.newBuilder.setValue(text))
    }
    b.build
  }

  /**
    * Parse a series of semi-colon delimited mesos attribute expressions, according to the spec described in
    * http://mesos.apache.org/documentation/latest/attributes-resources/
    */
  private def parseAttrs(expression: String): Seq[Protos.Attribute] =
    expression.split(';').filter(_.nonEmpty).map(parseAttr(_)).to[Seq]

  private def makeOffer(hostname: String, attributes: String = "", domainInfo: Option[DomainInfo] = None) = {
    val builder = Offer.newBuilder
      .setId(OfferID(Random.nextString(9)))
      .setSlaveId(SlaveID(Random.nextString(9)))
      .setFrameworkId(FrameworkID(Random.nextString(9)))
      .setHostname(hostname)
      .addAllAttributes(parseAttrs(attributes).asJava)
    domainInfo.foreach(builder.setDomain)
    builder.build
  }

  private def makeInstance(appId: PathId, attributes: String = "", host: String = "host", region: Option[String] = None, zone: Option[String] = None): Instance = {
    TestInstanceBuilder.newBuilder(appId).addTaskWithBuilder().taskRunning()
      .withNetworkInfo(hostName = Some(host), hostPorts = Seq(999))
      .build()
      .withAgentInfo(hostName = Some(host), region = region, zone = zone, attributes = Some(parseAttrs(attributes)))
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
