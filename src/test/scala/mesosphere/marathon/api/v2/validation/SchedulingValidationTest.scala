package mesosphere.marathon
package api.v2.validation

import com.wix.accord.scalatest.ResultMatchers
import com.wix.accord.validate
import mesosphere.{ UnitTest, ValidationTestLike }

class SchedulingValidationTest extends UnitTest with ValidationTestLike with ResultMatchers {

  import SchedulingValidation._
  import SchedulingValidationMessages._

  "SchedulingValidation" when {
    "validating bogus operator" should {
      "fail with human readable error message" in {
        validate(Seq("a", "b", "c"))(complyWithAppConstraintRules) should failWith(RuleViolationMatcher(
          constraint = ConstraintOperatorInvalid
        ))
      }
    }
    "validating CLUSTER constraint" should {
      def validAppConstraint(subtitle: String, c: Seq[String]): Unit = {
        subtitle in {
          validate(c)(complyWithAppConstraintRules) should be(aSuccess)
        }
      }
      def failsAsExpected(subtitle: String, c: Seq[String], violatedConstraint: String): Unit = {
        subtitle in {
          validate(c)(complyWithAppConstraintRules) should failWith(RuleViolationMatcher(
            constraint = violatedConstraint
          ))
        }
      }
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
  }
}
