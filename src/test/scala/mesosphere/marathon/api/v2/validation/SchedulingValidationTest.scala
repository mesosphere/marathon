package mesosphere.marathon
package api.v2.validation

import mesosphere.{ UnitTest, ValidationTestLike }

class SchedulingValidationTest extends UnitTest with ValidationTestLike {

  import SchedulingValidation._
  import SchedulingValidationMessages._

  "SchedulingValidation" when {
    "validating bogus operator" should {
      "fail with human readable error message" in {
        complyWithAppConstraintRules(Seq("a", "b", "c")) should haveViolations("/" -> ConstraintOperatorInvalid)
      }
    }
    def validAppConstraint(subtitle: String, c: Seq[String]): Unit = {
      subtitle in {
        complyWithAppConstraintRules(c) should be(aSuccess)
      }
    }
    def failsAsExpected(subtitle: String, c: Seq[String], violatedConstraint: String): Unit = {
      subtitle in {
        complyWithAppConstraintRules(c) should haveViolations("/" -> violatedConstraint)
      }
    }
    "validating CLUSTER constraint" should {

      behave like validAppConstraint("lowercase cluster operator", Seq("foo", "cluster", "bar"))
      behave like validAppConstraint("CLUSTER hostname field and no value", Seq("hostname", "CLUSTER"))
      behave like validAppConstraint("CLUSTER hostname field and empty value", Seq("hostname", "CLUSTER", ""))
      behave like validAppConstraint("CLUSTER hostname field and value", Seq("hostname", "CLUSTER", "somehost"))

      behave like validAppConstraint("CLUSTER non-hostname field with a value", Seq("attr", "CLUSTER", "foo"))
      behave like validAppConstraint("CLUSTER non-hostname field w/o value", Seq("a", "CLUSTER"))
      behave like validAppConstraint("CLUSTER non-hostname field w/ empty value", Seq("a", "CLUSTER"))

      behave like failsAsExpected("CLUSTER w/ empty field w/ value", Seq("", "CLUSTER", "b"), IllegalConstraintSpecification)
      behave like failsAsExpected("CLUSTER w/ empty field w/ empty value", Seq("", "CLUSTER", ""), IllegalConstraintSpecification)
      behave like failsAsExpected("CLUSTER w/ empty field w/o value", Seq("", "CLUSTER"), IllegalConstraintSpecification)
    }

    "validation of other constraints" should {

      val unique = Seq("hostname", "UNIQUE")
      val groupBy = Seq("rack-id", "GROUP_BY")
      val like = Seq("rack-id", "LIKE")
      val unlike = Seq("rack-id", "UNLIKE")
      val max_per = Seq("rack-id", "MAX_PER")

      behave like validAppConstraint("A UNIQUE constraint without a value", unique)
      behave like failsAsExpected("A UNIQUE constraint with a value", unique :+ "a", ConstraintUniqueDoesNotAcceptValue)

      behave like validAppConstraint("A GROUP_BY without a value", groupBy)
      behave like validAppConstraint("A GROUP_BY with a numeric value", groupBy :+ "123")
      behave like failsAsExpected("A GROUP_BY with a non-numeric value", groupBy :+ "AbcDZ", ConstraintGroupByMustBeEmptyOrInt)

      behave like failsAsExpected("A MAX_PER without a value", max_per, ConstraintMaxPerRequiresInt)
      behave like validAppConstraint("A MAX_PER with a numeric value", max_per :+ "123")
      behave like failsAsExpected("A MAX_PER with a non-numeric value", max_per :+ "AbcDZ", ConstraintMaxPerRequiresInt)

      behave like validAppConstraint("An IS with a text value", Seq("attribute", "IS", "value-1.2/3_4"))
      behave like failsAsExpected("An IS with a range value", Seq("attribute", "IS", "[0-9]"), IsOnlySupportsText)

      Seq(like, unlike).foreach { op =>
        behave like failsAsExpected(s"A ${op(1)} without a value", op, ConstraintLikeAnUnlikeRequireRegexp)
        behave like validAppConstraint(s"A ${op(1)} with a valid regex", op :+ ".*")
        behave like failsAsExpected(s"A ${op(1)} with an invalid regex", op :+ "*", InvalidRegularExpression)
      }
    }
  }
}
