package mesosphere.marathon
package raml

import mesosphere.UnitTest

class ConstraintConversionTest extends UnitTest {
  def checkConstraintConversion(constraints: Constraint*): Unit = {
    "all constraint operators are checked" in {
      constraints.map(_.operator).toSet shouldBe ConstraintOperator.all.toSet withClue (
        "We need to ensure that a case for every constraint operator is checked")
    }

    constraints.foreach { constraint =>
      s"convert ${constraint.operator} to and from array form, and protobuf format losslessly" in {
        // pods use the RAML constraint operator, while apps use the array format
        // We convert through all of these representations and back, making sure that no information is lost
        val roundTripped = constraint.fromRaml[Protos.Constraint]
          .toRaml[Seq[String]]
          .fromRaml[Protos.Constraint]
          .toRaml[Constraint]

        roundTripped shouldBe constraint
      }
    }
  }

  "ConstraintConversion" should {
    checkConstraintConversion(
      Constraint("foo", ConstraintOperator.GroupBy, Some("test")),
      Constraint("foo", ConstraintOperator.Is, Some("test")),
      Constraint("@hostname", ConstraintOperator.Unique, None),
      Constraint("rack", ConstraintOperator.MaxPer, Some("3")),
      Constraint("@hostname", ConstraintOperator.Unlike, Some("regex")),
      Constraint("@hostname", ConstraintOperator.Like, Some("regex")),
      Constraint("@hostname", ConstraintOperator.Cluster, None))
  }
}
