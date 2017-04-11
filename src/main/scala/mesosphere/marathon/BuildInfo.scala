package mesosphere.marathon

import java.util.jar.{ Attributes, Manifest }
import scala.Predef._
import scala.util.Try
import scala.util.control.NonFatal

case object BuildInfo {
  lazy val DefaultMajor = 1
  lazy val DefaultMinor = 5
  lazy val DefaultPatch = 0

  lazy val DefaultBuildVersion = s"$DefaultMajor.$DefaultMinor.$DefaultPatch-SNAPSHOT"

  lazy val manifest: Option[Manifest] = Try {
    val mf = new Manifest()
    mf.read(getClass().getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF"))
    mf
  }.toOption

  lazy val attributes: Option[Attributes] = manifest.map(_.getMainAttributes())

  def getAttribute(name: String): Option[String] = attributes.flatMap { attrs =>
    try {
      Option(attrs.getValue(name))
    } catch {
      case NonFatal(_) => None
    }
  }

  lazy val name: String = getAttribute("Implementation-Title").getOrElse("unknown")

  lazy val version: String = getAttribute("Implementation-Version").getOrElse(DefaultBuildVersion)

  lazy val scalaVersion: String = getAttribute("Scala-Version").getOrElse("2.x.x")

  lazy val buildref: String = getAttribute("Git-Commit").getOrElse("unknown")

  override val toString: String = {
    "name: %s, version: %s, scalaVersion: %s, buildref: %s" format (
      name, version, scalaVersion, buildref
    )
  }
}
