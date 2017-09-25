package mesosphere.marathon

import java.util.jar.{ Attributes, Manifest }
import scala.Predef._
import scala.util.control.NonFatal
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.io.IO

case object BuildInfo {
  private val marathonJar = "\\bmesosphere\\.marathon\\.marathon-[0-9.]+".r
  lazy val DefaultMajor = 1
  lazy val DefaultMinor = 5
  lazy val DefaultPatch = 0

  lazy val DefaultBuildVersion = s"$DefaultMajor.$DefaultMinor.$DefaultPatch-SNAPSHOT"

  /**
    * sbt-native-package provides all of the files as individual JARs. By default, `getResourceAsStream` returns the
    * first matching file for the first JAR in the class path. Instead, we need to enumerate through all of the
    * manifests, and find the one that applies to the Marathon application jar.
    */
  private lazy val marathonManifestPath: List[java.net.URL] =
    getClass().getClassLoader().getResources("META-INF/MANIFEST.MF").toIterator.filter { manifest =>
      marathonJar.findFirstMatchIn(manifest.getPath).nonEmpty
    }.toList

  lazy val manifest: Option[Manifest] = marathonManifestPath match {
    case Nil => None
    case List(file) =>
      val mf = new Manifest()
      IO.using(file.openStream) { f =>
        mf.read(f)
        Some(mf)
      }
    case otherwise =>
      throw new RuntimeException(s"Multiple marathon JAR manifests returned! ${otherwise}")
  }

  lazy val attributes: Option[Attributes] = manifest.map(_.getMainAttributes())

  def getAttribute(name: String): Option[String] = attributes.flatMap { attrs =>
    try {
      Option(attrs.getValue(name))
    } catch {
      case NonFatal(_) => None
    }
  }

  lazy val name: String = getAttribute("Implementation-Title").getOrElse("unknown")

  // IntelliJ has its own manifest.mf that will inject a version that doesn't necessarily match
  // our actual version. This can cause Migrations to fail since the version number doesn't correctly match up.
  lazy val version: String = getAttribute("Implementation-Version").filterNot(_ == "0.1-SNAPSHOT").getOrElse(DefaultBuildVersion)

  lazy val scalaVersion: String = getAttribute("Scala-Version").getOrElse("2.x.x")

  lazy val buildref: String = getAttribute("Git-Commit").getOrElse("unknown")

  override val toString: String = {
    "name: %s, version: %s, scalaVersion: %s, buildref: %s" format (
      name, version, scalaVersion, buildref
    )
  }
}
