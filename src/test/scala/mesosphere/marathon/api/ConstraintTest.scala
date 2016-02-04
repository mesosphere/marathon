package mesosphere.marathon.api

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import org.scalatest.Matchers
import play.api.libs.json.{ JsResultException, Json }

class ConstraintTest extends MarathonSpec with Matchers {

  test("Deserialize") {
    def shouldMatch(json: String, field: String, operator: Constraint.Operator, value: String = "") {
      import mesosphere.marathon.api.v2.json.Formats._
      val constraint = Json.fromJson[Constraint](Json.parse(json)).get
      assert(field == constraint.getField)
      assert(operator == constraint.getOperator)
      assert(value == constraint.getValue)
    }

    shouldMatch("""["hostname","UNIQUE"]""", "hostname", Constraint.Operator.UNIQUE)
    shouldMatch("""["rackid","GROUP_BY","1"]""", "rackid", Constraint.Operator.GROUP_BY, "1")
    shouldMatch("""["jdk","LIKE","7"]""", "jdk", Constraint.Operator.LIKE, "7")
    shouldMatch("""["jdk","UNLIKE","7"]""", "jdk", Constraint.Operator.UNLIKE, "7")
  }

  test("Read should allow only valid Constraints (regression for #2951)") {
    import mesosphere.marathon.api.v2.json.Formats._
    Json.parse("""[]""").asOpt[Constraint] should be(empty)
    Json.parse("""["foo", "UNKNOWN"]""").asOpt[Constraint] should be(empty)
    Json.parse("""["foo", "UNKNOWN", "bla"]""").asOpt[Constraint] should be(empty)
    Json.parse("""["foo", "CLUSTER", "bla"]""").asOpt[Constraint] should be(defined)
    Json.parse("""["foo", "CLUSTER", "bla", "bla2"]""").asOpt[Constraint] should be(empty)
    val ex = intercept[JsResultException](Json.parse("""["foo", "CLUSTER", "bla", "bla2"]""").as[Constraint])
    ex.errors should have size 1
    ex.errors.head._2 should have size 1
    ex.errors.head._2.head.messages.head should startWith("Constraint definition must be an array of string")

  }

  test("Read should give a nice validation error for unknown operators (regression for #3161)") {
    import mesosphere.marathon.api.v2.json.Formats._
    val ex = intercept[JsResultException](Json.parse("""["foo", "unique"]""").as[Constraint])
    ex.errors should have size 1
    ex.errors.head._2 should have size 1
    ex.errors.head._2.head.messages.head should startWith("Constraint operator must be one of the following")
  }

  test("Serialize") {
    def shouldMatch(expected: String, constraint: Constraint) {
      import mesosphere.marathon.api.v2.json.Formats._
      JsonTestHelper.assertThatJsonOf(constraint).correspondsToJsonString(expected)
    }

    shouldMatch("""["hostname","UNIQUE"]""", Constraint.newBuilder.setField("hostname")
      .setOperator(Constraint.Operator.UNIQUE).build)
    shouldMatch("""["rackid","GROUP_BY","1"]""", Constraint.newBuilder.setField("rackid")
      .setOperator(Constraint.Operator.GROUP_BY).setValue("1").build)
    shouldMatch("""["jdk","LIKE","7"]""", Constraint.newBuilder.setField("jdk")
      .setOperator(Constraint.Operator.LIKE).setValue("7").build)
    shouldMatch("""["jdk","UNLIKE","7"]""", Constraint.newBuilder.setField("jdk")
      .setOperator(Constraint.Operator.UNLIKE).setValue("7").build)
  }
}
