package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.state.PathId
import org.scalatest.{ FunSuiteLike, GivenWhenThen, Matchers }

class InMemRejectOfferCollectorTest extends FunSuiteLike with GivenWhenThen with Matchers {

  test("addRejection and stats cyclic") {
    val collector = new InMemRejectOfferCollector(3)
    val appId = PathId("test")
    collector.addRejection(appId, RejectionReason(Set(NoMatch("cpu", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    collector.addRejection(appId, RejectionReason(Set(NoMatch("ram", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    collector.addRejection(appId, RejectionReason(Set(NoMatch("mem", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    collector.addRejection(appId, RejectionReason(Set(NoMatch("ram", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    val result = collector.getStatsFor(appId)

    result.stats.allKeys should have size 2
    result.stats.count("ram(2)") shouldEqual 2
    result.count shouldEqual 3
  }

  test("addRejection and stats") {
    val collector = new InMemRejectOfferCollector(5)
    val appId = PathId("test")
    collector.addRejection(appId, RejectionReason(
      Set(
      NoMatch("cpu", 2, 1, ScalarMatchResult.Scope.NoneDisk),
      NoMatch("ram", 2, 1, ScalarMatchResult.Scope.NoneDisk)),
      Set(makeConstraint("jdk", Constraint.Operator.LIKE, "\\[6-7\\]"))))

    collector.addRejection(appId, RejectionReason(Set(NoMatch("ram", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    collector.addRejection(appId, RejectionReason(Set(NoMatch("mem", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    collector.addRejection(appId, RejectionReason(Set(NoMatch("ram", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    val result = collector.getStatsFor(appId)

    result.stats.allKeys should have size 3
    result.stats.count("ram(2)") shouldEqual 2
    result.stats.count("""cpu(2) + ram(2) + field: "jdk" operator: LIKE value: "\\[6-7\\]" """) shouldEqual 1
    result.count shouldEqual 4

    val noResult = collector.getStatsFor(PathId("test1"))
    noResult.count shouldEqual 0
  }

  test("addRejection under two keys") {
    val collector = new InMemRejectOfferCollector(5)
    val appId1 = PathId("test1")
    val appId2 = PathId("test2")

    collector.addRejection(appId1, RejectionReason(Set(NoMatch("ram", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    collector.addRejection(appId2, RejectionReason(Set(NoMatch("mem", 2, 1, ScalarMatchResult.Scope.NoneDisk)), Set()))

    val result = collector.getStatsFor(appId1)

    result.stats.allKeys should have size 1
    result.stats.count("ram(2)") shouldEqual 1
    result.count shouldEqual 1

    val result2 = collector.getStatsFor(appId2)
    result2.count shouldEqual 1
  }

  private def makeConstraint(field: String, operator: Operator, value: String) = {
    Constraint.newBuilder
      .setField(field)
      .setOperator(operator)
      .setValue(value)
      .build
  }
}
