package mesosphere.marathon
package util

import scala.util.matching.Regex

case class SemanticVersion(major: Int, minor: Int, patch: Int) {

  override val toString: String = s"$major.$minor.$patch"
}

object SemanticVersion {
  val Pattern: Regex = """(\d+)\.(\d+)\.(\d+).*""".r

  val zero = SemanticVersion(0, 0, 0)

  // If we can not match the version pattern we prefer to throw an
  // error rather than silently construct a dummy version.
  def apply(version: String): Option[SemanticVersion] = version match {
    case Pattern(major, minor, patch) =>
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