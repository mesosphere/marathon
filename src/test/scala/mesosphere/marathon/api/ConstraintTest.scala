package mesosphere.marathon
package api

import mesosphere.UnitTest
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.api.v2.validation.SchedulingValidation
import mesosphere.marathon.raml.Raml
import play.api.data.validation.ValidationError
import play.api.libs.json._

class ConstraintTest extends UnitTest {

  implicit val constraintReads: Reads[Constraint] = Reads { js =>
    import Validation._
    try {
      JsSuccess(Raml.fromRaml(validateOrThrow(js.as[Seq[String]])(SchedulingValidation.complyWithAppConstraintRules)))
    } catch {
      case vfe: ValidationFailedException =>
        JsError(ValidationError(messages = vfe.failure.violations.map(_.constraint)(collection.breakOut)))
    }
  }

  implicit val constraintWrites: Writes[Constraint] = Writes { c =>
    import mesosphere.marathon.raml._
    val converted = c.toRaml[Seq[String]]
    JsArray(converted.map(JsString))
  }

  "Constraints" should {
    "Deserialize" in {
      def shouldMatch(json: String, field: String, operator: Constraint.Operator, value: String = ""): Unit = {
        val constraint = Json.fromJson[Constraint](Json.parse(json)).get
        assert(field == constraint.getField)
        assert(operator == constraint.getOperator)
        assert(value == constraint.getValue)
      }

      shouldMatch("""["hostname","UNIQUE"]""", "hostname", Constraint.Operator.UNIQUE)
      shouldMatch("""["hostname","MAX_PER","1"]""", "hostname", Constraint.Operator.MAX_PER, "1")
      shouldMatch("""["rackid","GROUP_BY","1"]""", "rackid", Constraint.Operator.GROUP_BY, "1")
      shouldMatch("""["jdk","LIKE","7"]""", "jdk", Constraint.Operator.LIKE, "7")
      shouldMatch("""["jdk","UNLIKE","7"]""", "jdk", Constraint.Operator.UNLIKE, "7")
    }

    "Read should allow only valid Constraints (regression for #2951)" in {

      Json.parse("""[]""").asOpt[Constraint] should be(empty)
      Json.parse("""["foo", "UNKNOWN"]""").asOpt[Constraint] should be(empty)
      Json.parse("""["foo", "UNKNOWN", "bla"]""").asOpt[Constraint] should be(empty)
      Json.parse("""["foo", "CLUSTER", "bla"]""").asOpt[Constraint] should be(defined)
      Json.parse("""["foo", "CLUSTER", "bla", "bla2"]""").asOpt[Constraint] should be(empty)
      val ex = intercept[JsResultException](Json.parse("""["foo", "CLUSTER", "bla", "bla2"]""").as[Constraint])
      ex.errors should have size 1
      ex.errors.head._2 should have size 1
      ex.errors.head._2.head.messages.head should startWith("Each constraint must have either 2 or 3 fields")

    }

    "Read should give a nice validation error for unknown operators (regression for #3161)" in {

      val ex = intercept[JsResultException](Json.parse("""["foo", "unique"]""").as[Constraint])
      ex.errors should have size 1
      ex.errors.head._2 should have size 1
      ex.errors.head._2.head.messages.head should startWith("Constraint operator must be one of the following")
    }

    "Serialize" in {
      def shouldMatch(expected: String, constraint: Constraint): Unit = {

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
}
