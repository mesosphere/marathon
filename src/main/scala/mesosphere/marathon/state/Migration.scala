package mesosphere.marathon.state

import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.BuildInfo
import scala.annotation.implicitNotFound

@implicitNotFound("Could not find Migration instance for ${A}")
trait Migration[A] {
  def needsMigration(version: StorageVersion): Boolean
  def migrate(version: StorageVersion, obj: A): A
}

object StorageVersions {
  val VersionRegex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def current: StorageVersion = {
    BuildInfo.version match {
      case VersionRegex(major, minor, patch) =>
        StorageVersion
          .newBuilder()
          .setMajor(major.toInt)
          .setMinor(minor.toInt)
          .setPatch(patch.toInt)
          .build()
    }
  }

  def empty: StorageVersion = {
    StorageVersion
      .newBuilder()
      .setMajor(0)
      .setMinor(0)
      .setPatch(0)
      .build()
  }
}
