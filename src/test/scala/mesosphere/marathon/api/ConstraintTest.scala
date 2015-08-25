package mesosphere.marathon.api

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import play.api.libs.json.Json

class ConstraintTest extends MarathonSpec {

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
