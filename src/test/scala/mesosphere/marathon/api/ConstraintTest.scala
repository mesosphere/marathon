package mesosphere.marathon.api

import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.json.MarathonModule
import mesosphere.marathon.MarathonSpec

class ConstraintTest extends MarathonSpec {

  test("Deserialize") {
    val mapper = new ObjectMapper
    mapper.registerModule(new MarathonModule)

    def shouldMatch(json: String, field: String, operator: Constraint.Operator, value: String = "") {
      val constraint = mapper.readValue(json, classOf[Constraint])
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
    val mapper = new ObjectMapper
    mapper.registerModule(new MarathonModule)

    def shouldMatch(expected: String, constraint: Constraint) {
      val actual = mapper.writeValueAsString(constraint)
      assert(expected == actual)
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
