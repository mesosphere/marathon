package mesosphere.marathon
package raml

object Constraints {
  def apply(c: StringifiedConstraint*): Set[Seq[String]] =
    c.map(_.toSeq)(collection.breakOut)

  sealed trait StringifiedConstraint {
    def toSeq: Seq[String]
  }

  implicit class FieldOperator(fo: (String, String)) extends StringifiedConstraint {
    override def toSeq: Seq[String] = Seq(fo._1, fo._2)
  }

  implicit class FieldOperatorValue(fov: (String, String, String)) extends StringifiedConstraint {
    override def toSeq: Seq[String] = Seq(fov._1, fov._2, fov._3)
  }
}