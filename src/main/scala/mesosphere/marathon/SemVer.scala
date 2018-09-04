package mesosphere.marathon

/**
  * Models a Marathon version
  */
case class SemVer(major: Int, minor: Int, build: Int, commit: Option[String]) extends Ordered[SemVer] {
  override def toString(): String = commit match {
    case Some(c) => s"$major.$minor.$build-$c"
    case None => s"$major.$minor.$build"
  }

  override def compare(that: SemVer): Int = Seq(
    major.compare(that.major),
    minor.compare(that.minor),
    build.compare(that.build),
    commit.getOrElse("").compare(that.commit.getOrElse("")))
    .find(_ != 0)
    .getOrElse(0)
}

object SemVer {
  val empty = SemVer(0, 0, 0, None)

  // Matches e.g. 1.7.42 or v1.7.42
  val versionPattern = """^(\d+)\.(\d+)\.(\d+)$""".r

  // Matches e.g. 1.7.42-deadbeef or v1.7.42-deadbeef
  val versionCommitPattern = """^v?(\d+)\.(\d+)\.(\d+)-(\w+)$""".r

  /**
    * Create SemVer from string which has the form if 1.7.42-deadbeef.
    */
  def apply(version: String): SemVer =
    version match {
      case versionCommitPattern(major, minor, build, commit) =>
        apply(major.toInt, minor.toInt, build.toInt, Some(commit))
      case versionPattern(major, minor, build) =>
        apply(major.toInt, minor.toInt, build.toInt)
      case _ =>
        throw new IllegalArgumentException(s"Could not parse version $version.")
    }

  def apply(major: Int, minor: Int, build: Int): SemVer =
    apply(major, minor, build, None)
}
