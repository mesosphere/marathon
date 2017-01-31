package mesosphere.marathon
package raml

import mesosphere.UnitTest

class ConstraintConversionTest extends UnitTest {
  "ConstraintConversion" should {
    "A Constraint can be transformed into a Seq[String]" in {
      Given("A constraint proto")
      val constraint = Protos.Constraint.newBuilder()
        .setField("foo")
        .setOperator(Protos.Constraint.Operator.GROUP_BY)
        .setValue("test")
        .build()

      When("The constraint is be converted")
      val seq = constraint.toRaml[Seq[String]]

      Then("The constraint is a correct string sequence")
      seq should be(Seq("foo", "GROUP_BY", "test"))
    }
  }
}
