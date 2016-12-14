package mesosphere.marathon.util

case class SemanticVersion(major: Int, minor: Int, patch: Int) {

  override def toString(): String = Array(major, minor, patch).mkString(".")
}

object SemanticVersion {
  val pattern = """(\d+)\.(\d+)\.(\d+).*""".r

  // If we can not match the version pattern we prefer to throw an
  // error rather than silently construct a dummy version.
  def apply(version: String): Option[SemanticVersion] = version match {
    case pattern(major, minor, patch) =>
      Some(new SemanticVersion(major.toInt, minor.toInt, patch.toInt))
    case _ => None
  }

  implicit class OrderedSemanticVersion(val version: SemanticVersion) extends AnyVal with Ordered[SemanticVersion] {
    override def compare(that: SemanticVersion): Int = {
      def by(left: Int, right: Int, fn: => Int): Int = if (left.compareTo(right) != 0) left.compareTo(right) else fn
      by(version.major, that.major, by(version.minor, that.minor, by(version.patch, that.patch, 0)))
    }
  }
}